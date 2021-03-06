/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package com.dc2f.api.edit.ktor

import com.dc2f.Website
import com.dc2f.api.edit.*
import com.dc2f.api.edit.dto.ErrorResponse
import com.dc2f.util.Dc2fConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.*
import io.ktor.config.ApplicationConfig
import io.ktor.features.*
import io.ktor.features.NotFoundException
import io.ktor.http.*
import io.ktor.jackson.JacksonConverter
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.routing.routing
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import io.ktor.websocket.WebSockets
import org.slf4j.bridge.SLF4JBridgeHandler
import java.nio.file.FileSystems
import java.time.Duration
import java.util.concurrent.TimeUnit

private val logger = mu.KotlinLogging.logger {}

@UseExperimental(KtorExperimentalAPI::class)
fun <T : Website<*>> parse(dc2fEditApiConfig: ApplicationConfig): EditApiConfig<T> {
    return EditApiConfig(
        loadSetup(dc2fEditApiConfig.property(CONFIG_SETUP_CLASS).getString()),
        dc2fEditApiConfig.property(CONFIG_SECRET).getString(),
        requireNotNull(FileSystems.getDefault().getPath(dc2fEditApiConfig.property(CONTENT_DIRECTORY).getString())),
        dc2fEditApiConfig.propertyOrNull(CONFIG_STATIC_DIRECTORY)?.getString()
    )
}

fun <T: Website<*>> loadSetup(className: String): Dc2fConfig<T> {
    @Suppress("UNCHECKED_CAST")
    return EditApiConfig::class.java.classLoader.loadClass(className).newInstance() as Dc2fConfig<T>
}

@Suppress("unused")
@UseExperimental(KtorExperimentalAPI::class)
fun Application.dc2fEditorApi() {

    val dc2fEditApiConfig = environment.config.config("dc2f")
    val deps = Deps<Website<*>>(parse(dc2fEditApiConfig))
    dc2fEditorApi(deps)
}

@UseExperimental(KtorExperimentalAPI::class)
fun Application.dc2fEditorApi(deps: Deps<in Website<*>>) {

    environment.monitor.subscribe(ApplicationStopping) {
        log.warn("Application is stopping. Closing connections.")
//        deps.close()
    }

    installFeatures()
//    finalyzerRouting(deps)
    routing {
        apiRouting(deps)
    }
}

@UseExperimental(KtorExperimentalAPI::class)
fun Application.installFeatures() {
    install(StatusPages) {
        exception<NotFoundException> { cause ->
            logger.trace(cause) { "Handling Not Found Exception. ${cause.message}" }
            call.respond(HttpStatusCode.NotFound)
        }
        exception<Throwable> { cause ->
            logger.error(cause) { "Error during request ${call.request.uri}" }
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.toString()))
        }
    }
    install(CallLogging) {
        filter { false }
    }
    install(CustomCallLogFeature)
    install(WebSockets) {
        pingPeriod = Duration.ofMinutes(1)
    }
    install(ContentNegotiation) {
        // sendBeacon() in chrome only allows sending text/plain or form data, so just treat it as json.
        val j = JacksonConverter(jacksonObjectMapper().also { it.configureObjectMapper() })
        register(ContentType.Application.Json, j)
        register(ContentType.Text.Plain, j)
    }
}

@Suppress("unused")
class EditApi<WEBSITE: Website<*>>(val editApiConfig: EditApiConfig<WEBSITE>) {
    fun serve() {
//        val applicationEnvironment = commandLineEnvironment(emptyArray())
        val deps = editApiConfig.deps

        val applicationEnvironment = applicationEngineEnvironment {
            connector {
                this.host = "127.0.0.1"
                this.port = 8000
            }
            module {
                @Suppress("UNCHECKED_CAST")
                dc2fEditorApi(deps as Deps<Website<*>>)
            }
        }
        val engine = embeddedServer(Netty, applicationEnvironment) {

        }
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                logger.info { "Initiating shutdown..." }
                engine.stop(1, 5, TimeUnit.SECONDS)
            }
        })

        engine.start(wait = false)
        logger.info { "Started.."}
        while (true) {
            try {
                Thread.sleep(5000)
                logger.trace { "Still running ;-)"}
            } catch (e: InterruptedException) {
                logger.info { "We have been interrupted." }
                engine.stop(5, 8, TimeUnit.SECONDS)
                break
            }
        }
//        Thread.currentThread().join()
        logger.info { "Existing ..." }
//        while (true) {
//            val line = readLine()
//            if (arrayOf("stop", "quit").contains(line)) {
//                logger.info { "Stopping" }
//                engine.stop(5, 5, TimeUnit.SECONDS)
//                logger.info { "Called stop." }
//                break
//            } else {
//                logger.info { "Invalid input: $line" }
//            }
//        }
    }
}

fun main(args: Array<String>) {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    val applicationEnvironment = commandLineEnvironment(args)
    val engine = embeddedServer(Netty, applicationEnvironment) {
    }
    engine.start()
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            logger.info { "Initiating shutdown..." }
            engine.stop(1, 5, TimeUnit.SECONDS)
        }
    })
}
