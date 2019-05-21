package com.dc2f.api.edit

import app.anlage.site.FinalyzerTheme
import app.anlage.site.contentdef.*
import com.dc2f.*
import com.dc2f.api.edit.dto.ApiDto
import com.dc2f.render.*
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.*
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.dataformat.yaml.*
import com.fasterxml.jackson.module.kotlin.*
import io.ktor.application.*
import io.ktor.features.NotFoundException
import io.ktor.http.ContentType
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.KtorExperimentalAPI
import mu.KotlinLogging
import java.io.*
import java.lang.reflect.Modifier
import java.nio.file.*
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

private val yamlObjectMapper =
    ObjectMapper(
        Dc2fYAMLFactory()
            .configure(YAMLGenerator.Feature.INDENT_ARRAYS, true)
            .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false)
    )

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

private val om = jacksonObjectMapper()
    .also { it.configureObjectMapper() }
    .registerModule(SimpleModule().setSerializerModifier(ContentDefSerializerModifier()))

@ApiDto
data class ReflectTypeResponse(
    val types: Map<String, ContentDefReflection<*>>
)

@ApiDto
class CreateTransaction(
    val path: String,
    val contentPath: String
) {

    @get:JsonIgnore
    val contentPathValue
        get() = ContentPath.parse(contentPath)
}

@KtorExperimentalAPI
fun Route.apiRouting(deps: Deps<*>) {
    val typeReflectionCache =
        mutableMapOf<KClass<out ContentDef>, ContentDefReflection<out ContentDef>>()

    fun dataForCall(call: ApplicationCall): Pair<ContentDef, ContentDefMetadata> {
        logger.debug { "Got request. ${call.parameters.getAll("path")}" }
        val path =
            call.parameters.getAll("path")?.joinToString("/")
                ?: "/"

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

        require(metadata.path == contentPath)

        return content to metadata
    }

    fun <T : ContentDef> reflectionForType(clazz: KClass<out T>) =
        typeReflectionCache.computeIfAbsent(clazz) { ContentDefReflection(it) }

    get("/") {
        call.respondText("Hello world.")
    }
    route("/api") {
        get("/render/{path...}") {
            val (content, _) = dataForCall(call)

            val out = StringWriter()
            SinglePageStreamRenderer(
                FinalyzerTheme(),
                deps.context,
                (deps.rootContent!!.content as FinalyzerWebsite).config.url,
                AppendableOutput(out)
            ).renderContent(
                content,
                requireNotNull(deps.content.metadata.childrenMetadata[content]),
                null,
                OutputType.html
            )
            call.respondText(out.toString(), ContentType.Text.Html)
        }
        get("/type/") {
            val reflected = requireNotNull(call.request.queryParameters.getAll("type"))
                .map { className ->
                    @Suppress("UNCHECKED_CAST")
                    val clazz = Class.forName(className).kotlin as KClass<out ContentDef>
                    require(clazz.isSubclassOf(ContentDef::class)) {
                        "We only allow reflection of subclasses of ContentDef"
                    }
                    className to reflectionForType(clazz = clazz)
                }
                .toMap()
            call.respond(om.writeValueAsString(ReflectTypeResponse(reflected)))
        }
        get("/reflect/{path...}") {

            val (content, metadata) = dataForCall(call)
            val contentDefClass = requireNotNull(metadata.contentDefClass)
            require(contentDefClass.isInstance(content)) {
                "content must be an instance of $contentDefClass - actual: $content, ${content::class}"
            }

            val fsPath = requireNotNull(metadata.fsPath)

            val tree = ObjectMapper(YAMLFactory()).readTree(Files.readAllBytes(fsPath))
            val root = om.createObjectNode()

            root.set(
                "breadcrumbs",
                om.valueToTree(
                    generateSequence(metadata.path) {
                        it.takeUnless(ContentPath::isRoot)?.parent()
                    }
                        .map { mapOf("name" to it.name, "path" to it.toString()) }
                        .toList()
                        .reversed()
                )
            )
//            root.set("breadcrumbs", contentPath.parent())
            val reflection = reflectionForType(contentDefClass)
            root.set("reflection", om.valueToTree(reflection))

            root.set("content", tree)
            root.set("children", om.createObjectNode().also { obj ->
                obj.setAll(metadata.directChildren.mapValues {
                    val (key, value) = it
                    om.createArrayNode().also { arr ->
                        arr.addAll(value.map { contentDefChild ->
                            om.createObjectNode().apply {
                                put("path", contentDefChild.loadedContent.metadata.path.toString())
                                put("isProperty", contentDefChild.isProperty)
                                val prop = reflection.property[key]
                                if (prop != null) {
                                    if (prop is ContentDefPropertyReflectionParsable) {
                                        put(
                                            "rawContent",
                                            (contentDefChild.loadedContent.content as ParsableContentDef).rawContent()
                                        )
                                    }
                                }
                            }
                        })
                    }
                })
            })

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

        post("/createChild/begin/{path...}") {
            val (content, metadata) = dataForCall(call)
            val parentFsPath = requireNotNull(metadata.fsPath?.parent)
            val create = call.receive<ContentCreate>()
            val parentReflection = reflectionForType(content::class)
            val prop = requireNotNull(parentReflection.property[create.property]) {
                "Unknown property ${create.property} for type ${content::class}"
            }
            require(prop.multiValue) { "Currently we only support multi value properties." }
            require(prop is ContentDefPropertyReflectionNested)
            val type =
                requireNotNull(prop.allowedTypes[create.typeIdentifier]) { "Invalid type identifier ${create.typeIdentifier} for ${prop.name} (types: ${prop.allowedTypes}" }
            @Suppress("UNCHECKED_CAST")
            val reflection = reflectionForType(Class.forName(type).kotlin as KClass<out ContentDef>)

            val siblings = prop.getValue(content)
            val prefix = if (siblings != null && siblings is Collection<*>) {
                val last = siblings.last() as ContentDef
                val lastMetadata = requireNotNull(metadata.childrenMetadata[last])
//                requireNotNull(lastMetadata.fsPath).fileName.toString().split('.')
//                    .first()
//                    .toIntOrNull()
//                    ?.let { String.format("%03d.", it + 1) }
                lastMetadata.comment?.toIntOrNull()?.let { String.format("%03d.", it + 1) }
            } else {
                "000."
            }
            val fileName = arrayOf(prefix, create.slug, ".", create.typeIdentifier).filterNotNull()
                .joinToString("")
            val directory = parentFsPath.resolve(fileName)
            val relativeDirectory = deps.contentRootPath.relativize(directory)

            Files.createDirectory(directory)
//            Files.write(
//                directory.resolve(INDEX_YAML_NAME),
//                yamlObjectMapper.writeValueAsBytes(create.content)
//            )

            savePropertiesForContentDef(
                yamlObjectMapper.createObjectNode(),
                directory,
                create.content,
                reflection,
                directory.resolve(INDEX_YAML_NAME)
            )

            call.respond(
                om.writeValueAsString(
                    mapOf(
                        "status" to "ok",
                        "transaction" to deps.messageTransformer.transformWrite(
                            om.writeValueAsString(
                                CreateTransaction(
                                    relativeDirectory.toString(),
                                    metadata.path.child(create.slug).toString()
                                )
                            )
                        )
                    )
                )
            )
        }
        post("/createChild/upload/{path...}") {
            val transactionValue = requireNotNull(call.request.header("x-transaction"))
            val transaction = om.readValue<CreateTransaction>(
                requireNotNull(
                    deps.messageTransformer.transformRead(transactionValue)
                )
            )
            val multipart = call.receiveMultipart()

            val directory = deps.contentRootPath.resolve(transaction.path)

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val bareName = File(part.originalFileName).name
                        val uploadFile = File(directory.toFile(), bareName)
                        part.streamProvider().use { input ->
                            uploadFile.outputStream().buffered().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    is PartData.FormItem -> {
                        logger.debug { "Got a form item? ${part.value}" }
                    }
                    else -> throw UnsupportedOperationException("We currently only support file items, got a $part")
                }
            }
            call.respond(om.writeValueAsString(mapOf("status" to "ok")))
        }

        post("/createChild/commit") {
            val transactionValue = requireNotNull(call.request.header("x-transaction"))
            val transaction = om.readValue<CreateTransaction>(
                requireNotNull(
                    deps.messageTransformer.transformRead(transactionValue)
                )
            )
            deps.reload()
            call.respond(
                om.writeValueAsString(
                    mapOf(
                        "status" to "ok",
                        "path" to transaction.contentPath.toString()
                    )
                )
            )
        }

        patch("/update/{path...}") {
            val (content, metadata) = dataForCall(call)
            val reflection = reflectionForType(content::class)
            val modification = call.receive<ContentModification>()
            val updates = modification.updates
            val fsPath = requireNotNull(metadata.fsPath)
            val fsPathDirectory = fsPath.parent
            val jsonRootNode = yamlObjectMapper.readTree(Files.readAllBytes(fsPath)) as ObjectNode

            val remaining = savePropertiesForContentDef(
                jsonRootNode,
                fsPathDirectory,
                updates,
                reflection,
                fsPath
            )

            logger.debug { "Properties not saved $remaining" }

            deps.reload()

            call.respond(
                om.writeValueAsString(
                    mapOf(
                        "status" to "ok",
                        "unsaved" to remaining.keys
                    )
                )
            )
        }
    }
}

private fun savePropertiesForContentDef(
    jsonRootNode: ObjectNode,
    fsPathDirectory: Path,
    updates: ObjectNode,
    reflection: ContentDefReflection<out ContentDef>,
    fsPath: Path
): Map<String, JsonNode> {
    var jsonRootNodeChanged = false

    fun saveProperty(prop: ContentDefPropertyReflection, value: JsonNode): Boolean {
        val key = prop.name
        return when (prop) {
            is ContentDefPropertyReflectionParsable -> {
                if (jsonRootNode.has(prop.name)) {
                    jsonRootNode.set(prop.name, value)
                    true
                } else {
                    require(prop.parsableTypes.size == 1) { "Currently we don't support parsable types which are ambigious ($prop)" }
                    val type = prop.parsableTypes.keys.single()
                    require(value is TextNode)
                    Files.write(
                        fsPathDirectory.resolve("@${prop.name}.$type"),
                        value.textValue().toByteArray()
                    )
                    true
                }
            }
            is ContentDefPropertyReflectionPrimitive -> {
                if (prop.multiValue) {
                    jsonRootNode.set(prop.name, value as ArrayNode)
                } else {
                    jsonRootNode.set(prop.name, value)
                    //                        require(!prop.multiValue)
                    //                        when (prop.type) {
                    //                            PrimitiveType.Boolean -> jsonRootNode.put(prop.name, value as Boolean)
                    //                            PrimitiveType.String -> jsonRootNode.put(prop.name, value as String)
                    //                            PrimitiveType.ZonedDateTime -> TODO() //jsonRootNode.put(prop.)
                    //                            PrimitiveType.Unknown -> TODO()
                    //                        }
                }
                jsonRootNodeChanged = true
                return true
            }
            is ContentDefPropertyReflectionContentReference,
            is ContentDefPropertyReflectionFileAsset -> jsonRootNode.set(
                prop.name,
                value
            ).let { true }
            is ContentDefPropertyReflectionNested -> {
                require(!prop.multiValue)
                jsonRootNode.set(prop.name, value as ObjectNode)
                jsonRootNodeChanged = true
                return true
            }
            is ContentDefPropertyReflectionMap -> TODO()
        }
    }

    val remaining = updates.fields().asSequence().filterNot { entry ->
        saveProperty(
            requireNotNull(reflection.property[entry.key]) { "Unable to find property ${entry.key}" },
            entry.value
        )
    }.map { it.key to it.value }.toMap()
//            val remaining = modification.updates.filterNot { (key, value) ->
//                saveProperty(requireNotNull(reflection.property[key]), value)
//            }

    if (jsonRootNodeChanged) {
        Files.write(fsPath, yamlObjectMapper.writeValueAsBytes(jsonRootNode))
    }
    return remaining
}

data class ContentCreate(
    /**
     * The type identifier for the created content.
     */
    val typeIdentifier: String,
    /**
     * the name of the property in the parent under which to create the child.
     */
    val property: String,
    /**
     * The name of the new content.
     */
    val slug: String,
    val content: ObjectNode
)

@ApiDto
data class ContentModification(
    val updates: ObjectNode
)

@ApiDto
class ContentDefReflection<T : ContentDef>(@JsonIgnore val klass: KClass<T>) {

    @JsonIgnore
    val contentLoader = ContentLoader(klass)

    val property by lazy {
        properties.map { it.name to it }.toMap()
    }
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
                        require(!prop.isMultiValue) {
                            "MultiValue parsable values are not (yet) supported. ${klass}::${prop.name}"
                        }
                        val propertyTypes = findPropertyTypesFor(prop, elementJavaClass)
                        ContentDefPropertyReflectionParsable(
                            prop.name,
                            prop.returnType.isMarkedNullable,
                            prop.isMultiValue,
                            propertyTypes.keys.joinToString(","),
                            propertyTypes
                        )
                    } else {
                        elementJavaClass.kotlin.isAbstract
                        ContentDefPropertyReflectionNested(
                            prop.name,
                            prop.returnType.isMarkedNullable,
                            prop.isMultiValue,
                            (contentLoader.findChildTypesForProperty(prop.name)?.map { type ->
                                type.key to type.value.name
                            }?.toMap() ?: emptyMap()) +
                                // TODO i don't think this is necessary actually?
                                findPropertyTypesFor(
                                    prop,
                                    elementJavaClass
                                ).mapValues { it.value.java.name },
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
                } else if (BaseFileAsset::class.java.isAssignableFrom(elementJavaClass)) {
                    ContentDefPropertyReflectionFileAsset(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        ImageAsset::class.java.isAssignableFrom(elementJavaClass)
                            .then { ContentDefPropertyReflectionFileAsset.Type.Image }
                            ?: ContentDefPropertyReflectionFileAsset.Type.File
                    )
                } else if (ContentReference::class.java.isAssignableFrom(elementJavaClass)) {
                    ContentDefPropertyReflectionContentReference(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue
                    )
                } else {
                    ContentDefPropertyReflectionPrimitive(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        PrimitiveType.fromJavaClass(elementJavaClass, "${klass}::${prop.name}")
                    )
                }
            }
            .sortedWith(compareBy({ it.optional }, { it.name }))
//            .sortedBy { it.name }
    }

    private fun findPropertyTypesFor(property: KProperty<*>, clazz: Class<*>) =
        contentLoader.findPropertyTypes().filter { clazz.isAssignableFrom(it.value.java) }
}

@ApiDto
sealed class ContentDefPropertyReflection(
    val name: String,
    val optional: Boolean,
    val multiValue: Boolean
) {
    fun getValue(content: ContentDef): Any? {
        val property = requireNotNull(content::class.memberProperties.find { it.name == name }) {
            "Unable to find member property $name on $content (${content::class})"
        }
        return property.getter.run {
            isAccessible = true
            call(content)
        }
    }

    val kind
        get() = when (this) {
            is ContentDefPropertyReflectionParsable -> "Parsable"
            is ContentDefPropertyReflectionPrimitive -> "Primitive"
            is ContentDefPropertyReflectionMap -> "Map"
            is ContentDefPropertyReflectionNested -> "Nested"
            is ContentDefPropertyReflectionFileAsset -> "File"
            is ContentDefPropertyReflectionContentReference -> "ContentReference"
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
        fun fromJavaClass(elementJavaClass: Class<*>, debugMessage: kotlin.String): PrimitiveType {
            val kotlinClazz = elementJavaClass.kotlin
            val type = PrimitiveType.values().find { it.clazz == kotlinClazz }
            if (type == null) {
                logger.error { "UNKNOWN PrimitiveType: $kotlinClazz ($debugMessage)" }
                return Unknown
            }
            return type
        }

    }
}

class ContentDefPropertyReflectionParsable(
    name: String, optional: Boolean, multiValue: Boolean,
    val parsableHint: String, @JsonIgnore val parsableTypes: Map<String, KClass<out Any>>
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionMap(
    name: String, optional: Boolean, multiValue: Boolean,
    val mapValueType: String
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionNested(
    name: String, optional: Boolean, multiValue: Boolean,
    val allowedTypes: Map<String, String>, val baseType: String
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionFileAsset(
    name: String, optional: Boolean, multiValue: Boolean,
    val fileType: Type
) : ContentDefPropertyReflection(name, optional, multiValue) {
    enum class Type {
        File,
        Image
    }
}

class ContentDefPropertyReflectionContentReference(
    name: String, optional: Boolean, multiValue: Boolean
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
