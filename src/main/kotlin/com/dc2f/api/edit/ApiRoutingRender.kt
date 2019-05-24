package com.dc2f.api.edit

import com.dc2f.render.*
import io.ktor.application.call
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import mu.KotlinLogging
import java.io.StringWriter
import java.nio.file.*

private val logger = KotlinLogging.logger {}

fun Route.apiRoutingRender(deps: Deps<*>) {
    get("/static/{path...}") {
        val path = call.parameters.getAll("path")?.joinToString("/")
        val filePath = FileSystems.getDefault().getPath("./tmpDir").resolve(path)
        if (Files.exists(filePath)) {
            call.respondFile(filePath.toFile())
        } else if (deps.staticDirectory != null) {
            val fallback = FileSystems.getDefault().getPath(deps.staticDirectory).resolve(path)
            call.respondFile(fallback.toFile())
        } else {
            logger.warn { "Unable to find static content $path" }
            call.respond(HttpStatusCode.NotFound)
        }
    }
    route("/api") {
        get("/render/{path...}") {
            val (content, metadata) = dataForCall(deps, call)

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
            call.respondText(out.toString(), ContentType.Text.Html)
        }
    }
}
