package com.dc2f.api.edit.ratpack

import com.dc2f.Website
import com.dc2f.api.edit.*
import com.dc2f.api.edit.dto.Dc2fApi
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.reactivestreams.*
import ratpack.form.Form
import ratpack.handling.Context
import ratpack.http.Status
import ratpack.service.Service
import ratpack.server.*
import ratpack.service.StartEvent
import ratpack.service.StopEvent
import ratpack.websocket.WebSockets.*
import java.nio.file.*

private val logger = KotlinLogging.logger {}

class WebsocketPublisher : Publisher<String> {
    val subscribers = mutableListOf<Subscriber<in String>>()

    override fun subscribe(s: Subscriber<in String>) {
        subscribers.add(s)
        s.onSubscribe(object : Subscription {
            override fun cancel() {
                subscribers.remove(s)
            }

            override fun request(n: Long) {
            }
        })
    }

    fun publish(message: String) {
        subscribers.forEach {
            it.onNext(message)
        }
    }
}

val reloadPublisher = WebsocketPublisher()

class RatpackDc2fServer<WEBSITE: Website<*>>(val editApiConfig: EditApiConfig<WEBSITE>) {
    fun serve(port: Int? = null) {
        val deps = editApiConfig.deps
        deps.registerOnRefreshListener {
            reloadPublisher.publish("reload")
        }
        logger.info("Serving ...")
        RatpackServer.start { server ->
            server.serverConfig { config ->
//                it.baseDir()
                port?.let { config.port(it) }
            }
            server.registryOf {
                it.add(object : Service {
                    override fun onStart(event: StartEvent) {
                        logger.info("Starting server.")
                    }

                    override fun onStop(event: StopEvent?) {
                        logger.info("Stopping server.")
                        deps.close()
                    }
                })
            }
            server.handlers { chain ->
                chain.get("") { ctx ->
                    ctx.redirect("/api/render/")
                }

                chain.get("api/type/") { ctx ->
                    val types = ctx.request.queryParams.getAll("type")
                    ctx.respondJson(deps.handler.reflectTypes(types))
                }
                chain.get("api/reflect/:path:.*") { ctx->
                    val (content, metadata) = deps.handler.dataForUrlPath(ctx.path)
                    ctx.respondJson(deps.handler.reflectContentPath(content, metadata))
                }
                chain.get("api/createChild/begin/:path:.*") { ctx ->
                    val (content, metadata) = deps.handler.dataForUrlPath(ctx.path)
                    ctx.request.body.then {
                        deps.handler.createChildBegin(content, metadata, it.text)
                    }
                }
                chain.get("api/createChild/upload/:path:.*") { ctx ->
                    val transactionValue = ctx.request.headers[Dc2fApi.HEADER_TRANSACTION]
                    ctx.parse(Form::class.java).then { form ->
                        val files = form.files()
                        GlobalScope.launch {
                            deps.handler.createChildUpload(transactionValue, object : Dc2fUpload {
                                override suspend fun forEachPart(partHandler: suspend (UploadedFile) -> Unit) {
                                    for (file in files) {
                                        partHandler(UploadedFile(file.value.fileName) { file.value.inputStream })
                                    }
                                }
                            })
                        }
                    }
                }
                chain.patch("api/update/:path:.*") { ctx ->
                    val (content, metadata) = deps.handler.dataForUrlPath(ctx.path)
                    ctx.request.body.then {
                        GlobalScope.launch {
                            deps.handler.updatePath(content, metadata, it.text)
                        }
                    }
                }


                // Life Rendering below.
                chain.get("api/render/:path:.*") { ctx ->
//                    ctx.render("We should render ${ctx.pathTokens["path"]}")
                    val (content, metadata) = deps.handler.dataForUrlPath(ctx.path)
                    ctx.response.send("text/html", deps.handler.renderContentToString(content, metadata))
                }
                chain.get("static/:path:.*") { ctx ->
                    val path = ctx.pathTokens["path"]
                    val filePath = deps.staticTempOutputDirectory.resolve(path)
                    when {
                        Files.exists(filePath) -> ctx.render(filePath)
                        deps.staticDirectory != null -> {
                            val fallback =
                                FileSystems.getDefault().getPath(deps.staticDirectory).resolve(path)
                            ctx.render(fallback)
                        }
                        else -> {
                            logger.warn { "Unable to find static content $path" }
                            ctx.response.status(Status.NOT_FOUND).send("Path not found: $path")
                        }
                    }
                }
                chain.get("api/ws") { ctx ->
                    websocketBroadcast(ctx, reloadPublisher)
                }
            }
        }
    }
}

private val Context.path get() = pathTokens["path"] ?: "/"

private fun Context.respondJson(jsonString: String) {
    response.contentType("application/json").send(jsonString)
}
