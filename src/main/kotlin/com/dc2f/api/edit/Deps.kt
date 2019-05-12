package com.dc2f.api.edit

import com.dc2f.*
import io.ktor.config.ApplicationConfig
import mu.KotlinLogging
import sun.plugin.dom.exception.InvalidStateException
import java.nio.file.*
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

@Suppress("EXPERIMENTAL_API_USAGE")
class Deps<T: ContentDef>(val dc2fEditApiConfig: ApplicationConfig, private val rootContentClass: KClass<T>) {
    init {
        loadContent()
    }

    var rootContent: LoadedContent<T>? = null
    var loaderContext: LoaderContext? = null
    val content : LoadedContent<T> get() = rootContent ?: throw InvalidStateException("Not yet ready.")
    val context : LoaderContext get() = loaderContext ?: throw InvalidStateException("Not yet ready.")

    private fun loadContent() {
        val path = dc2fEditApiConfig.property("contentDirectory")
        val loader = ContentLoader(rootContentClass)
        loader.load(FileSystems.getDefault().getPath(path.getString())) { loadedWebsite, context ->
            this.rootContent = loadedWebsite
            this.loaderContext = context
            logger.debug { "Loaded website $loadedWebsite" }
        }
    }
}
