package com.dc2f.api.edit

import com.dc2f.render.*
import io.ktor.application.call
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.KtorExperimentalAPI
import io.ktor.websocket.webSocket
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.consumeEach
import mu.KotlinLogging
import java.io.StringWriter
import java.nio.file.*

private val logger = KotlinLogging.logger {}

private val members = mutableListOf<WebSocketSession>()

suspend fun notifyMembers() {
    members.forEach {session ->
        session.send("reload")
    }
}

@KtorExperimentalAPI
@UseExperimental(ObsoleteCoroutinesApi::class)
fun Route.apiRoutingRender(deps: Deps<*>) {
    get("/static/{path...}") {
        val path = call.parameters.getAll("path")?.joinToString("/")
        val filePath = FileSystems.getDefault().getPath("./tmpDir").resolve(path)
        when {
            Files.exists(filePath) -> call.respondFile(filePath.toFile())
            deps.staticDirectory != null -> {
                val fallback = FileSystems.getDefault().getPath(deps.staticDirectory).resolve(path)
                call.respondFile(fallback.toFile())
            }
            else -> {
                logger.warn { "Unable to find static content $path" }
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
    route("/api") {
        webSocket("/ws") {
            members.add(this)
            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        logger.debug("received frame: ${frame.readText()}")
                    }
                }
            } finally {
                members.remove(this)
            }
        }

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
            val response = out.toString().replace("</head>", "<script>$jsInject</script></head>")
            call.respondText(response, ContentType.Text.Html)
        }
    }
}


// language="ECMAScript 6"
private val jsInject = """
const socket = new WebSocket('ws://localhost:8012/api/ws');

// Connection opened
socket.addEventListener('open', function (event) {
    socket.send('Hello Server!');
    console.log('we are connected.', event);
});

// Listen for messages
socket.addEventListener('message', function (event) {
    console.log('Message from server ', event.data);
    if (event.data === 'reload') {
        window.location.reload();
    }
});

""".trimIndent()
