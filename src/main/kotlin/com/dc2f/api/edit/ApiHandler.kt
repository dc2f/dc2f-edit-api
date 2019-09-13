package com.dc2f.api.edit

import com.dc2f.*
import com.dc2f.render.*
import java.io.StringWriter
import java.nio.file.FileSystems

class NotFoundException(message: String) : Exception(message) {

}

class ApiHandler(val deps: Deps<*>) {
    fun dataForUrlPath(path: String): Pair<ContentDef, ContentDefMetadata> {
        val contentPath = ContentPath.parse(path)

        val content =
            deps.context.contentByPath[contentPath]
                ?: throw NotFoundException("Unable to find rootContent by path.")
        val metadata = if (deps.content.metadata.path == contentPath) {
            deps.content.metadata
        } else {
            deps.context.metadata[content]
//        deps.content.metadata.childrenMetadata[content]
        }
            ?: throw NotFoundException("Unable to find metadata.")

        require(metadata.path == contentPath)

        return content to metadata
    }

    fun renderContentToString(content: ContentDef, metadata: ContentDefMetadata): String {
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
        return out.toString().replace("</head>", "<script>$jsInject</script></head>")
    }
}

// language="ECMAScript 6"
private val jsInject = """
const host = window.location.host;
const socket = new WebSocket('ws://'+host+'/api/ws');

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
