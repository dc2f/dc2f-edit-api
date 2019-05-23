package com.dc2f.api.edit

import com.dc2f.*
import com.dc2f.render.UrlConfig
import com.dc2f.util.Dc2fSetup
import io.ktor.config.ApplicationConfig
import io.ktor.sessions.SessionTransportTransformerMessageAuthentication
import mu.KotlinLogging
import sun.plugin.dom.exception.InvalidStateException
import java.nio.file.FileSystems
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

class SimpleMessageTransformer(val secret: String) : MessageTransformer {

    val transformer = SessionTransportTransformerMessageAuthentication(secret.toByteArray())

    override fun transformRead(content: String): String? {
        return transformer.transformRead(Base64.getDecoder().decode(content).toString(Charsets.UTF_8))
    }

    override fun transformWrite(content: String): String {
        return Base64.getEncoder().encodeToString(transformer.transformWrite(content).toByteArray())
    }

}

private fun <T : Website<*>> loadSetup(className: String): Dc2fSetup<T> {
    val setup = Class.forName(className).newInstance()
    @Suppress("UNCHECKED_CAST")
    return setup as Dc2fSetup<T>
}


@Suppress("EXPERIMENTAL_API_USAGE")
class Deps<T: Website<*>>(private val dc2fEditApiConfig: ApplicationConfig) {
//    init {
//        loadContent()
//    }
    val setup = loadSetup<T>(dc2fEditApiConfig.property(CONFIG_SETUP_CLASS).getString())

    val content : LoadedContent<T> get() = rootContent ?: throw InvalidStateException("Not yet ready.")
    val context : LoaderContext get() = loaderContext ?: throw InvalidStateException("Not yet ready.")
    val messageTransformer : MessageTransformer = SimpleMessageTransformer(dc2fEditApiConfig.property(CONFIG_SECRET).getString())
    val contentRootPath = requireNotNull(FileSystems.getDefault().getPath(dc2fEditApiConfig.property(CONTENT_DIRECTORY).getString()))
    var loaderContext: LoaderContext? = null
    var rootContent: LoadedContent<T> = loadContent()
    val urlConfig: UrlConfig get() = setup.urlConfig(rootContent.content)

    val staticDirectory: String? = dc2fEditApiConfig.propertyOrNull(CONFIG_STATIC_DIRECTORY)?.getString()

    private fun loadContent(): LoadedContent<T> {
        val loader = ContentLoader(setup.rootContent)
        return loader.load(contentRootPath) { loadedWebsite, context ->
            @Suppress("UNCHECKED_CAST")
            this.rootContent = loadedWebsite as LoadedContent<T>
            this.loaderContext = context
            logger.debug { "Loaded website $loadedWebsite" }
            loadedWebsite
        }
    }

    fun reload() {
        loadContent()
    }
}
