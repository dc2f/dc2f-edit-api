package com.dc2f.api.edit

import app.anlage.site.contentdef.WebsiteFolderContent
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
import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

private val logger = KotlinLogging.logger {}


private val <R> KProperty<R>.isMultiValue: Boolean
    get() =
        (returnType.classifier as? KClass<*>)?.isSubclassOf(Collection::class) == true

private val <R> KProperty<R>.isTransient: Boolean
    get() =
        javaField?.modifiers?.let {
            Modifier.isTransient(it)
        } == true

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
    val typeReflectionCache =
        mutableMapOf<KClass<out ContentDef>, ContentDefReflection<out ContentDef>>()

    fun <T : ContentDef> reflectionForType(clazz: KClass<out T>) =
        typeReflectionCache.computeIfAbsent(clazz) { ContentDefReflection(it) }

    get("/") {
        call.respondText("Hello world.")
    }
    route("/api") {
        get("/reflect/{path...}") {
            logger.debug { "Got request. ${call.parameters.getAll("path")}" }
            val path =
                call.parameters.getAll("path")?.joinToString("/")
                    ?: "/"
            val parameters = call.parameters

            val contentPath = ContentPath.parse(path)

            val content =
                deps.context.contentByPath[contentPath]
                    ?: throw NotFoundException("Unable to find rootContent by path.")
            val metadata = if (deps.content.metadata.path == contentPath) {
                deps.content.metadata
            } else {
                deps.content.metadata.childrenMetadata[content]
            }
                ?: throw NotFoundException("Unable to find metadata.")
            val fsPath = requireNotNull(metadata.fsPath)
            val om = jacksonObjectMapper()
                .also { it.configureObjectMapper() }
                .registerModule(SimpleModule().setSerializerModifier(ContentDefSerializerModifier()))

            val tree = ObjectMapper(YAMLFactory()).readTree(Files.readAllBytes(fsPath))
            val root = om.createObjectNode()

            root.set(
                "breadcrumbs",
                om.valueToTree(
                    generateSequence(contentPath) {
                        it.takeUnless(ContentPath::isRoot)?.parent()
                    }
                        .map { mapOf("name" to it.name, "path" to it.toString()) }
                        .toList()
                        .reversed()
                )
            )
//            root.set("breadcrumbs", contentPath.parent())
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

            val reflection = reflectionForType(content::class)
            root.set("reflection", om.valueToTree(reflection))

            root.set("types",
                om.valueToTree(
                    reflection.properties
                        .mapNotNull { it as? ContentDefPropertyReflectionNested }
                        .flatMap { it.allowedTypes.values + listOf(it.baseType) }
                        .distinct()
                        .map {
                            @Suppress("UNCHECKED_CAST")
                            it to reflectionForType(Class.forName(it).kotlin as KClass<ContentDef>)
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

    val properties by lazy {
        klass.memberProperties.filter { !it.returnType.isJavaType }
            .filter { prop ->
                if (!prop.isLateinit) {
                    true
                } else {
                    if (!prop.isTransient) {
                        throw IllegalArgumentException("a lateinit field must be marked as transient.")
                    }
                    false
                }
            }
            .map { prop ->
                val elementJavaClass = prop.elementJavaClass
                if (ContentDef::class.java.isAssignableFrom(prop.elementJavaClass)) {
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
                            }?.toMap() ?: emptyMap()) + findPropertyTypesFor(
                                prop,
                                elementJavaClass
                            ),
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
                        PrimitiveType.fromJavaClass(elementJavaClass)
                    )
                }
            }
    }

    private fun findPropertyTypesFor(property: KProperty<*>, clazz: Class<*>) =
        contentLoader.findPropertyTypes().filter { clazz.isAssignableFrom(it.value.java) }
            .mapValues { it.value.java.name }
}

@ApiDto
sealed class ContentDefPropertyReflection(
    val name: String,
    val optional: Boolean,
    val multiValue: Boolean
) {
    val kind
        get() = when (this) {
            is ContentDefPropertyReflectionParsable -> "Parsable"
            is ContentDefPropertyReflectionPrimitive -> "Primitive"
            is ContentDefPropertyReflectionMap -> "Map"
            is ContentDefPropertyReflectionNested -> "Nested"
        }
}

class ContentDefPropertyReflectionPrimitive(
    name: String, optional: Boolean, multiValue: Boolean,
    val type: PrimitiveType
) : ContentDefPropertyReflection(name, optional, multiValue)

enum class PrimitiveType(val clazz: KClass<*>) {
    Boolean(kotlin.Boolean::class),
    String(kotlin.String::class),
    //    Integer(Integer::class),
    ZonedDateTime(java.time.ZonedDateTime::class),
    Unknown(Any::class)

    ;

    companion object {
        fun fromJavaClass(elementJavaClass: Class<*>): PrimitiveType {
            val kotlinClazz = elementJavaClass.kotlin
            val type = PrimitiveType.values().find { it.clazz == kotlinClazz }
            if (type == null) {
                logger.error { "UNKNOWN PrimitiveType: $kotlinClazz" }
                return Unknown
            }
            return type
        }

    }
}

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
