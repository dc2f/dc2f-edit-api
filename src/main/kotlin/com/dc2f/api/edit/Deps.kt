package com.dc2f.api.edit

import com.dc2f.*
import com.dc2f.render.UrlConfig
import com.dc2f.util.*
import mu.KotlinLogging
import sun.plugin.dom.exception.InvalidStateException
import java.io.Closeable
import java.nio.file.Path
import java.util.*

private val logger = KotlinLogging.logger {}

const val CONFIG_SECRET = "secret"
const val CONTENT_DIRECTORY = "contentDirectory"
const val CONFIG_STATIC_DIRECTORY = "staticDirectory"
const val CONFIG_SETUP_CLASS = "setupClass"

interface MessageTransformer {
    fun transformRead(content: String): String?
    fun transformWrite(content: String): String
}

class SimpleMessageTransformer(secret: String) : MessageTransformer {

    private val transformer = SessionTransportTransformerMessageAuthentication(secret.toByteArray())

    override fun transformRead(content: String): String? {
        return transformer.transformRead(Base64.getDecoder().decode(content).toString(Charsets.UTF_8))
    }

    override fun transformWrite(content: String): String {
        return Base64.getEncoder().encodeToString(transformer.transformWrite(content).toByteArray())
    }

}

fun <T : Website<*>> loadSetup(className: String): Dc2fSetup<T> {
    val setup = Class.forName(className).newInstance()
    @Suppress("UNCHECKED_CAST")
    return setup as Dc2fSetup<T>
}

class EditApiConfig<T : Website<*>>(
    val setup: Dc2fSetup<T>,
    val secret: String,
    val contentRoot: Path,
    val staticRoot: String?
): Closeable {

    val deps by lazy {
        Deps(this)
    }

    override fun close() {
        if (this::deps.isLazyInitialized) {
            deps.close()
        }
    }
}


@Suppress("EXPERIMENTAL_API_USAGE")
class Deps<T : Website<*>>(val editApiConfig: EditApiConfig<T>): Closeable {
    //    init {
//        loadContent()
//    }
    val setup = editApiConfig.setup

    val content: LoadedContent<T> get() = rootContent
    val context: LoaderContext
        get() = loaderContext ?: throw InvalidStateException("Not yet ready.")
    val messageTransformer: MessageTransformer =
        SimpleMessageTransformer(editApiConfig.secret)
    val contentRootPath = editApiConfig.contentRoot
    private var loaderContext: LoaderContext? = null
    private var rootContent: LoadedContent<T> = loadContent()
    val urlConfig: UrlConfig get() = setup.urlConfig(rootContent.content)

    val staticDirectory: String? =
        editApiConfig.staticRoot

    private val onRefreshListeners = mutableListOf<suspend () -> Unit>()
    val handler by lazy {
        ApiHandler(this)
    }

    private fun loadContent(): LoadedContent<T> {
        val loader = ContentLoader(setup.rootContent)
        val (loadedWebsite, context) = loader.loadWithoutClose(contentRootPath)

        @Suppress("UNCHECKED_CAST")
        this.rootContent = loadedWebsite
        this.loaderContext = context
        logger.debug { "Loaded website ${loadedWebsite.content.name}" }
        return loadedWebsite
    }

    override fun close() {
        logger.info("closing.")
        loaderContext?.close()
        onRefreshListeners.clear()
    }

    fun registerOnRefreshListener(listener: suspend () -> Unit) {
        onRefreshListeners.add(listener)
    }
    fun removeOnRefreshListener(listener: suspend () -> Unit) =
        onRefreshListeners.remove(listener)

    suspend fun reload(content: ContentDef) {
//        loaderContext?.close()
//        loadContent()
        try {
            context.reload(content)
            onRefreshListeners.forEach { it() }
        } catch (e: Exception) {
            logger.error(e) { "Error while reloading content." }
        }
    }
}
