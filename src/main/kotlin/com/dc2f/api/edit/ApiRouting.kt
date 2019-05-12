package com.dc2f.api.edit

import app.anlage.site.contentdef.*
import com.dc2f.*
import com.dc2f.api.edit.dto.ApiDto
import com.dc2f.util.isJavaType
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.call
import io.ktor.features.NotFoundException
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.KtorExperimentalAPI
import mu.KotlinLogging
import java.nio.file.Files
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaType

private val logger = KotlinLogging.logger {}


private val <R> KProperty<R>.isMultiValue: Boolean
    get() =
        (returnType.classifier as? KClass<*>)?.isSubclassOf(Collection::class) == true

class UnknownClass

/// either the class of the return value, or the class of elements in the collection, if multivalue (ie. a collection)
private val <R> KProperty<R>.elementJavaClass: Class<*>
    get() =
        requireNotNull(
            if ((returnType.classifier as? KClass<*>)?.isSubclassOf(Map::class) == true) {
                Map::class
            } else {
                if (isMultiValue) {
                    returnType.arguments[0].type
                } else {
                    returnType
                }?.classifier as? KClass<*>
            }?.java
        ) {
            "Return Type is not a class: $returnType / ${returnType.javaType} (of property $this)"
        }

@KtorExperimentalAPI
fun Route.apiRouting(deps: Deps<*>) {
    get("/") {
        call.respondText("Hello world.")
    }
    route("/api") {
        get("/reflect/{path...}") {
            val path =
                call.parameters.getAll("path")?.joinToString("/")
                    ?: throw NotFoundException()
            val parameters = call.parameters

            val content =
                deps.context.contentByPath[ContentPath.parse(path)]
                    ?: throw NotFoundException("Unable to find rootContent by path.")
            val metadata = deps.content.metadata.childrenMetadata[content]
                ?: throw NotFoundException("Unable to find metadata.")
            val fsPath = requireNotNull(metadata.fsPath)
            val om = jacksonObjectMapper()
                .also { it.configureObjectMapper() }
                .registerModule(SimpleModule().setSerializerModifier(ContentDefSerializerModifier()))

            val tree = ObjectMapper(YAMLFactory()).readTree(Files.readAllBytes(fsPath))
            val root = om.createObjectNode()

            root.set("content", tree)
            root.set("children", om.createObjectNode().also { obj ->
                obj.setAll(metadata.directChildren.mapValues {
                    val (key, value) = it
                    om.createArrayNode().also { arr ->
                        arr.addAll(value.map { contentDefChild ->
                            om.createObjectNode().apply {
                                put("path", contentDefChild.loadedContent.metadata.path.toString())
                                put("isProperty", contentDefChild.isProperty)
                            }
                        })
                    }
                })
            })

            val reflection = ContentDefReflection(content::class)
            root.set("reflection", om.valueToTree(reflection))

            root.set("types",
                om.valueToTree(
                    reflection.properties.values
                        .mapNotNull { it as? ContentDefPropertyReflectionNested }
                        .flatMap { it.allowedTypes.values + listOf(it.baseType) }
                        .distinct()
                        .map {
                            @Suppress("UNCHECKED_CAST")
                            it to ContentDefReflection(Class.forName(it).kotlin as KClass<ContentDef>)
                        }
                        .toMap()
                )
            )

//            root.set("children")
//            om.writeValueAsString(root)
//            call.respond(om.writeValueAsString(InspectDto(rootContent)))
            call.respond(om.writeValueAsString(root))
        }
    }
}

@ApiDto
class ContentDefReflection<T : ContentDef>(@JsonIgnore val klass: KClass<T>) {

    @JsonIgnore
    val contentLoader = ContentLoader(klass)

    val properties
        get() = klass.memberProperties.filter { !it.returnType.isJavaType }.map { prop ->
            val elementJavaClass = prop.elementJavaClass
            prop.name to if (ContentDef::class.java.isAssignableFrom(prop.elementJavaClass)) {
                if (elementJavaClass.kotlin.companionObjectInstance is Parsable<*>) {
                    ContentDefPropertyReflectionParsable(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        elementJavaClass.name
                    )
                } else {
                    ContentDefPropertyReflectionNested(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        (contentLoader.findChildTypesForProperty(prop.name)?.map { type ->
                            type.key to type.value.name
                        }?.toMap() ?: emptyMap()) + findPropertyTypesFor(prop, elementJavaClass),
                        elementJavaClass.name
                    )
                }
            } else if (Map::class.java.isAssignableFrom(elementJavaClass)) {
                ContentDefPropertyReflectionMap(
                    prop.name,
                    prop.returnType.isMarkedNullable,
                    prop.isMultiValue,
                    requireNotNull(prop.returnType.arguments[1].type?.javaType?.typeName)
                )
            } else {
                ContentDefPropertyReflectionPrimitive(
                    prop.name,
                    prop.returnType.isMarkedNullable,
                    prop.isMultiValue,
                    elementJavaClass.name
                )
            }
        }.toMap()

    private fun findPropertyTypesFor(property: KProperty<*>, clazz: Class<*>) =
        contentLoader.findPropertyTypes().filter { clazz.isAssignableFrom(it.value.java) }
            .mapValues { it.value.java.name }
}

@ApiDto
sealed class ContentDefPropertyReflection(
    @JsonIgnore
    val name: String,
    val optional: Boolean,
    val multiValue: Boolean
) {
    val kind get() = when(this) {
        is ContentDefPropertyReflectionParsable -> "parsable"
        is ContentDefPropertyReflectionPrimitive -> "primitive"
        is ContentDefPropertyReflectionMap -> "map"
        is ContentDefPropertyReflectionNested -> "nested"
    }
}

class ContentDefPropertyReflectionPrimitive(
    name: String, optional: Boolean, multiValue: Boolean,
    val type: String
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionParsable(
    name: String, optional: Boolean, multiValue: Boolean,
    val parsableHint: String
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionMap(
    name: String, optional: Boolean, multiValue: Boolean,
    val mapValue: String
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionNested(
    name: String, optional: Boolean, multiValue: Boolean,
    val allowedTypes: Map<String, String>, val baseType: String
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefSerializer : JsonSerializer<ContentDef>() {
    override fun serialize(
        value: ContentDef?,
        gen: JsonGenerator?,
        serializers: SerializerProvider?
    ) {
//        if (value is WebsiteFolderContent) {
//            serializers?.defaultSerializeValue(value, gen)
//        } else {
        gen?.writeString("blubb")
//        }
    }

    override fun handledType(): Class<ContentDef> {
        return ContentDef::class.java
    }

}

class ContentDefSerializerModifier : BeanSerializerModifier() {
    override fun modifySerializer(
        config: SerializationConfig?,
        beanDesc: BeanDescription,
        serializer: JsonSerializer<*>
    ): JsonSerializer<*> {
        if (
            ContentDef::class.java.isAssignableFrom(beanDesc.beanClass) &&
            !WebsiteFolderContent::class.java.isAssignableFrom(beanDesc.beanClass)
        ) {
            return ContentDefSerializer()
        }
        return serializer
    }
}
