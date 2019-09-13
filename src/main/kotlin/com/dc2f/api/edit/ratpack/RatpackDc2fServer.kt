package com.dc2f.api.edit.ratpack

import com.dc2f.Website
import com.dc2f.api.edit.*
import mu.KotlinLogging
import org.reactivestreams.*
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
    fun serve() {
        val deps = editApiConfig.deps
        deps.registerOnRefreshListener {
            reloadPublisher.publish("reload")
        }
        logger.info("Serving ...")
        RatpackServer.start { server ->
            server.serverConfig {
//                it.baseDir()
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
                chain.get("api/render/:path:.*") { ctx ->
                    val path = ctx.pathTokens["path"]
//                    ctx.render("We should render ${ctx.pathTokens["path"]}")
                    val (content, metadata) = deps.handler.dataForUrlPath(path ?: "/")
                    ctx.response.send("text/html", deps.handler.renderContentToString(content, metadata))
                }
                chain.get("static/:path:.*") { ctx ->
                    val path = ctx.pathTokens["path"]
                    val filePath = FileSystems.getDefault().getPath("./tmpDir").resolve(path)
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