package com.dc2f.api.edit

import com.dc2f.*
import io.ktor.config.ApplicationConfig
import io.ktor.sessions.*
import mu.KotlinLogging
import sun.plugin.dom.exception.InvalidStateException
import java.nio.file.*
import java.util.*
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

const val CONFIG_SECRET = "secret"
const val CONTENT_DIRECTORY = "contentDirectory"

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



@Suppress("EXPERIMENTAL_API_USAGE")
class Deps<T: ContentDef>(val dc2fEditApiConfig: ApplicationConfig, private val rootContentClass: KClass<T>) {
//    init {
//        loadContent()
//    }

    val content : LoadedContent<T> get() = rootContent ?: throw InvalidStateException("Not yet ready.")
    val context : LoaderContext get() = loaderContext ?: throw InvalidStateException("Not yet ready.")
    val messageTransformer : MessageTransformer = SimpleMessageTransformer(dc2fEditApiConfig.property(CONFIG_SECRET).getString())
    val contentRootPath = requireNotNull(FileSystems.getDefault().getPath(dc2fEditApiConfig.property(CONTENT_DIRECTORY).getString()))
    var loaderContext: LoaderContext? = null
    var rootContent: LoadedContent<T> = loadContent()

    private fun loadContent(): LoadedContent<T> {
        val loader = ContentLoader(rootContentClass)
        return loader.load(contentRootPath) { loadedWebsite, context ->
            this.rootContent = loadedWebsite
            this.loaderContext = context
            logger.debug { "Loaded website $loadedWebsite" }
            loadedWebsite
        }
    }

    fun reload() {
        loadContent()
    }
}
