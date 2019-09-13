package com.dc2f.api.edit

import com.dc2f.*
import com.dc2f.loader.*
import com.dc2f.render.*
import com.dc2f.util.ApiDto
import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.*
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.*
import mu.KotlinLogging
import java.io.*
import java.nio.file.*
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

private val logger = KotlinLogging.logger {}

class NotFoundException(message: String) : Exception(message) {

}


private val yamlObjectMapper =
    ObjectMapper(
        Dc2fYAMLFactory()
            .configure(YAMLGenerator.Feature.INDENT_ARRAYS, true)
            .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false)
    )


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


class ApiHandler(val deps: Deps<*>) {
    val typeReflectionCache =
        mutableMapOf<KClass<out ContentDef>, ContentDefReflection<out ContentDef>>()

    fun <T : ContentDef> reflectionForType(clazz: KClass<out T>) =
        typeReflectionCache.computeIfAbsent(clazz) { ContentDefReflection(it) }

    fun dataForUrlPath(path: String): Pair<ContentDef, ContentDefMetadata> {
        val contentPath = ContentPath.parse(path)

        val content =
            deps.context.contentByPath[contentPath]
                ?: throw NotFoundException("Unable to find rootContent by path.")
        val metadata = if (deps.content.metadata.path == contentPath) {
            deps.content.metadata
        } else {
            deps.context.metadata[content]
//        deps.content.metadata.childrenMetadata[content]
        }
            ?: throw NotFoundException("Unable to find metadata.")

        require(metadata.path == contentPath)

        return content to metadata
    }

    fun renderContentToString(content: ContentDef, metadata: ContentDefMetadata): String {
        val out = StringWriter()
        val urlConfigTmp = deps.urlConfig.run {
            UrlConfig(
                protocol, host, "api/render/"
            )
        }
        val urlConfig = object :
            UrlConfig(urlConfigTmp.protocol, urlConfigTmp.host, urlConfigTmp.pathPrefix) {
            override val staticFilesPrefix: String
                get() = "static/"
        }
        SinglePageStreamRenderer(
            deps.setup.theme,
            deps.context,
            urlConfig,
            AppendableOutput(out),
            FileSystems.getDefault().getPath("./tmpDir")
        ).renderRootContent(
            content,
            metadata,
            OutputType.html
        )
        return out.toString().replace("</head>", "<script>$jsInject</script></head>")
    }

    fun reflectTypes(types: List<String>): String {
        val reflected = types
            .map { className ->
                @Suppress("UNCHECKED_CAST")
                val clazz = Class.forName(className).kotlin as KClass<out ContentDef>
                require(clazz.isSubclassOf(ContentDef::class)) {
                    "We only allow reflection of subclasses of ContentDef"
                }
                className to reflectionForType(clazz = clazz)
            }
            .toMap()
        return om.writeValueAsString(ReflectTypeResponse(reflected))
    }

    fun reflectContentPath(content: ContentDef, metadata: ContentDefMetadata): String {
        val contentDefClass = requireNotNull(metadata.contentDefClass)
        require(contentDefClass.isInstance(content)) {
            "content must be an instance of $contentDefClass - actual: $content, ${content::class}"
        }

        val fsPath = requireNotNull(metadata.fsPath)

        val tree = if (!Files.exists(fsPath)) {
            // for now _index.yml files are optional.
            yamlObjectMapper.createObjectNode()
        } else {
            yamlObjectMapper.readTree(Files.readAllBytes(fsPath))
        }
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
                                        (contentDefChild.loadedContent.content as ParsableObjectDef).rawContent()
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
        return om.writeValueAsString(root)

    }

    fun createChildBegin(
        content: ContentDef,
        metadata: ContentDefMetadata,
        create: ContentCreate
    ): String {
        val parentFsPath = requireNotNull(metadata.fsPath?.parent)
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

        return om.writeValueAsString(
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
    }

    suspend fun createChildCommit(transactionValue: String): String {
        val transaction = om.readValue<CreateTransaction>(
            requireNotNull(
                deps.messageTransformer.transformRead(transactionValue)
            )
        )
        val parent = requireNotNull(deps.context.contentByPath[transaction.contentPathValue])
        deps.reload(parent)
        return om.writeValueAsString(
            mapOf(
                "status" to "ok",
                "path" to transaction.contentPath
            )
        )
    }

    suspend fun updatePath(content: ContentDef, metadata: ContentDefMetadata, modification: ContentModification): String {
        val reflection = reflectionForType(content::class)
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

        deps.reload(content)
        return om.writeValueAsString(
            mapOf(
                "status" to "ok",
                "unsaved" to remaining.keys
            )
        )
    }

    suspend fun createChildUpload(transactionValue: String, upload: Dc2fUpload): String {

        val transaction = om.readValue<CreateTransaction>(
            requireNotNull(
                deps.messageTransformer.transformRead(transactionValue)
            )
        )

        val directory = deps.contentRootPath.resolve(transaction.path)


        upload.forEachPart { part ->
            val bareName = File(part.originalFileName).name
            val uploadFile = File(directory.toFile(), bareName)
            part.streamProvider().use { input ->
                uploadFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return om.writeValueAsString(mapOf("status" to "ok"))
    }
}

interface Dc2fUpload {
    suspend fun forEachPart(partHandler: suspend (UploadedFile) -> Unit)
}

data class UploadedFile(val originalFileName: String?, val streamProvider: () -> InputStream)

// language="ECMAScript 6"
private val jsInject = """
const host = window.location.host;
let gotReloadSignal = false;
let isReconnecting = false;

function connectToWebSocket() {
    let handledDisconnect = false;
    const socket = new WebSocket('ws://'+host+'/api/ws');
    
    // Connection opened
    socket.addEventListener('open', function (event) {
        socket.send('Hello Server!');
        console.log('we are connected.', event);
        if (isReconnecting) {
            gotReloadSignal = true;
            window.location.reload();
        }
    });
    
    // Listen for messages
    socket.addEventListener('message', function (event) {
        console.log('Message from server ', event.data);
        if (event.data === 'reload') {
            gotReloadSignal = true;
            window.location.reload();
        }
    });
    
    function maybeRetry() {
        console.log('maybeRetry: gotReloadSignal:' + gotReloadSignal + ' didRetry:" + didRetry');
        if (!gotReloadSignal && !handledDisconnect) {
            handledDisconnect = true;
            if (!isReconnecting) {
                isReconnecting = true;
                const body = document.querySelector('body');
                const div = document.createElement('div');
                div.classList.add('loading');
                div.style.position = 'fixed';// = "position: fixed; top: 0; left: 0; right: 0; bottom:0; background-color: #000000; opacity: 0.5;";
                div.style.top = div.style.right = div.style.bottom = div.style.left = 0;
                div.style.backgroundColor = '#000000';
                div.style.opacity = '0.5';
                div.style.zIndex = '10000';
                // noinspection JSCheckFunctionSignatures
                body.appendChild(div);
            }
            setTimeout(function() {
                connectToWebSocket();
            }, 300);
        }
    }
    
    socket.addEventListener('error', function (event) {
        console.log('Error from websocket. ', event);
        maybeRetry();
    });
    
    socket.addEventListener('close', function (event) {
        console.log('Closed websocket.', event);
        maybeRetry();
    })
}
connectToWebSocket();

""".trimIndent()




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
            !ContentDefNested::class.java.isAssignableFrom(beanDesc.beanClass)
        ) {
            return ContentDefSerializer()
        }
        return serializer
    }
}

fun ObjectMapper.configureObjectMapper() {
    enable(SerializationFeature.INDENT_OUTPUT)
    registerModule(JavaTimeModule().also {
    }/*.also { it.addSerializer(LocalDate::class.java, LocalDateSerializer()) }*/)
//            configOverride(LocalDateTime::class.java).format = JsonFormat.Value.forShape(JsonFormat.Shape.STRING)
    // Write timestamps ans Milliseconds, instead of seconds with nano decimals.
    configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
    configure(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
    configOverride(LocalDate::class.java).format = JsonFormat.Value.forPattern("yyyy-MM-dd")
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
            is ContentDefPropertyReflectionFileAsset,
                // TODO maybe add some validation for enums?
            is ContentDefPropertyReflectionEnum -> jsonRootNode.set(
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
