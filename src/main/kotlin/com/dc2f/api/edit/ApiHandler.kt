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
let gotReloadSignal = false;
let isReconnecting = false;

function connectToWebSocket() {
    let handledDisconnect = false;
    const socket = new WebSocket('ws://'+host+'/api/ws');
    
    // Connection opened
    socket.addEventListener('open', function (event) {
        socket.send('Hello Server!');
        console.log('we are connected.', event);
        if (isReconnecting) {
            gotReloadSignal = true;
            window.location.reload();
        }
    });
    
    // Listen for messages
    socket.addEventListener('message', function (event) {
        console.log('Message from server ', event.data);
        if (event.data === 'reload') {
            gotReloadSignal = true;
            window.location.reload();
        }
    });
    
    function maybeRetry() {
        console.log('maybeRetry: gotReloadSignal:' + gotReloadSignal + ' didRetry:" + didRetry');
        if (!gotReloadSignal && !handledDisconnect) {
            handledDisconnect = true;
            if (!isReconnecting) {
                isReconnecting = true;
                const body = document.querySelector('body');
                const div = document.createElement('div');
                div.classList.add('loading');
                div.style.position = 'fixed';// = "position: fixed; top: 0; left: 0; right: 0; bottom:0; background-color: #000000; opacity: 0.5;";
                div.style.top = div.style.right = div.style.bottom = div.style.left = 0;
                div.style.backgroundColor = '#000000';
                div.style.opacity = '0.5';
                div.style.zIndex = '10000';
                // noinspection JSCheckFunctionSignatures
                body.appendChild(div);
            }
            setTimeout(function() {
                connectToWebSocket();
            }, 300);
        }
    }
    
    socket.addEventListener('error', function (event) {
        console.log('Error from websocket. ', event);
        maybeRetry();
    });
    
    socket.addEventListener('close', function (event) {
        console.log('Closed websocket.', event);
        maybeRetry();
    })
}
connectToWebSocket();

""".trimIndent()