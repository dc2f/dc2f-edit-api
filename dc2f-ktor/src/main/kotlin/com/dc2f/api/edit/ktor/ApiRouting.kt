@file:Suppress("BlockingMethodInNonBlockingContext")

package com.dc2f.api.edit.ktor

import com.dc2f.*
import com.dc2f.api.edit.*
import com.dc2f.api.edit.dto.Dc2fApi
import io.ktor.application.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.KtorExperimentalAPI
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}


@KtorExperimentalAPI
fun dataForCall(deps: Deps<*>, call: ApplicationCall): Pair<ContentDef, ContentDefMetadata> {
    logger.debug { "Got request. ${call.parameters.getAll("path")}" }
    val path =
        call.parameters.getAll("path")?.joinToString("/")
            ?: "/"

    return deps.handler.dataForUrlPath(path)
}

@KtorExperimentalAPI
fun Route.apiRouting(deps: Deps<*>) {
    get("/") {
        call.respondText("Hello world.")
    }

    apiRoutingRender(deps)

    deps.registerOnRefreshListener {
        notifyMembers()
    }

    route("/api") {

        get("/type/") {
            val types = requireNotNull(call.request.queryParameters.getAll("type"))
            val response = deps.handler.reflectTypes(types)
            call.respond(response)
        }
        get("/reflect/{path...}") {

            val (content, metadata) = dataForCall(deps, call)
            val response = deps.handler.reflectContentPath(content, metadata)
            call.respond(response)
        }

        post("/createChild/begin/{path...}") {
            val (content, metadata) = dataForCall(deps, call)
            val create = call.receive<ContentCreate>()
            call.respond(deps.handler.createChildBegin(content, metadata, create))
        }

        post("/createChild/upload/{path...}") {
            val transactionValue = requireNotNull(call.request.header(Dc2fApi.HEADER_TRANSACTION))

            val multipart = call.receiveMultipart()

            val response = deps.handler.createChildUpload(transactionValue, object : Dc2fUpload {
                override suspend fun forEachPart(partHandler: suspend (UploadedFile) -> Unit) {
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                partHandler(UploadedFile(part.originalFileName, part.streamProvider))
                            }
                            is PartData.FormItem -> {
                                logger.debug { "Got a form item? ${part.value}" }
                            }
                            else -> throw UnsupportedOperationException("We currently only support file items, got a $part")
                        }
                    }
                }

            });

            call.respond(response)
        }

        post("/createChild/commit") {
            val transactionValue = requireNotNull(call.request.header("x-transaction"))
            val response = deps.handler.createChildCommit(transactionValue)
            call.respond(
                response
            )
        }

        patch("/update/{path...}") {
            val (content, metadata) = dataForCall(deps, call)
            val modification = call.receive<ContentModification>()


           call.respond(
                deps.handler.updatePath(content, metadata, modification)
            )
        }
    }
}




