/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package com.dc2f.api.edit

import com.dc2f.Website
import com.dc2f.api.edit.dto.ErrorResponse
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.*
import io.ktor.features.*
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
import java.time.*
import java.util.concurrent.TimeUnit

private val logger = mu.KotlinLogging.logger {}


@Suppress("unused")
@UseExperimental(KtorExperimentalAPI::class)
fun Application.dc2fEditorApi() {

    val dc2fEditApiConfig = environment.config.config("dc2f")
    val deps = Deps<Website<*>>(dc2fEditApiConfig)

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

internal fun ObjectMapper.configureObjectMapper() {
    enable(SerializationFeature.INDENT_OUTPUT)
    registerModule(JavaTimeModule().also {
    }/*.also { it.addSerializer(LocalDate::class.java, LocalDateSerializer()) }*/)
//            configOverride(LocalDateTime::class.java).format = JsonFormat.Value.forShape(JsonFormat.Shape.STRING)
    // Write timestamps ans Milliseconds, instead of seconds with nano decimals.
    configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
    configure(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
    configOverride(LocalDate::class.java).format = JsonFormat.Value.forPattern("yyyy-MM-dd")
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
