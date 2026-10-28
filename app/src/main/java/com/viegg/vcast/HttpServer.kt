package com.viegg.vcast

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

private val SERVICE_TYPES = mapOf(
    "AVTransport" to "urn:schemas-upnp-org:service:AVTransport:1",
    "RenderingControl" to "urn:schemas-upnp-org:service:RenderingControl:1",
    "ConnectionManager" to "urn:schemas-upnp-org:service:ConnectionManager:1"
)

private val SINK_PROTOCOL_INFO = listOf(
    "video/mp4", "video/x-matroska", "video/x-msvideo", "video/mpeg",
    "video/mpeg4", "video/quicktime", "video/webm", "video/x-flv",
    "video/x-ms-wmv", "video/3gpp", "video/vnd.dlna.mpeg-tts", "video/x-m4v",
    "application/vnd.apple.mpegurl", "video/h264", "video/hevc",
    "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-ms-wma", "audio/wav",
    "audio/flac", "audio/ogg", "audio/x-m4a", "audio/ac3", "audio/ape",
    "image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff", "image/webp"
).joinToString(",") { "http-get:*:$it:*" }

private data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

class HttpServer(
    private val context: Context,
    private val config: VcastConfig,
    private val state: TransportState,
    private val log: (String) -> Unit
) {
    private val description: String = buildDescription(config)
    private val scpd: Map<String, String> = mapOf(
        "AVTransport" to loadAsset(context, "AVTransport.xml"),
        "RenderingControl" to loadAsset(context, "RenderingControl.xml"),
        "ConnectionManager" to loadAsset(context, "ConnectionManager.xml")
    )

    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "http-conn").apply { isDaemon = true }
    }
    private val notifier = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "http-notify").apply { isDaemon = true }
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "http-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
        notifier.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                workers.execute { handleConnection(client) }
            } catch (e: Exception) {
                if (running) log("[http] accept error: ${e.message}")
            }
        }
    }

    private fun handleConnection(client: Socket) {
        client.use {
            it.soTimeout = 30_000
            val input = BufferedInputStream(it.getInputStream())
            val output = it.getOutputStream()
            val request = readRequest(input, output) ?: return
            dispatch(request, output)
        }
    }

    private fun readRequest(input: BufferedInputStream, output: OutputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (headerBytes.size() > 64 * 1024) return null
        }

        val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val method = parts[0].uppercase(Locale.US)
        val rawUri = parts[1]
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase(Locale.US)] =
                    line.substring(index + 1).trim()
            }
        }

        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        val declaredLength = headers["content-length"]?.toIntOrNull() ?: 0
        val length = declaredLength.coerceIn(0, 4 * 1024 * 1024)
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }

        val path = try {
            URLDecoder.decode(rawUri.substringBefore('?'), "UTF-8")
        } catch (_: Exception) {
            rawUri.substringBefore('?')
        }
        return HttpRequest(method, path, headers, body)
    }

    private fun dispatch(request: HttpRequest, output: OutputStream) {
        when (request.method) {
            "GET", "HEAD" -> handleGet(request, output)
            "POST" -> handlePost(request, output)
            "SUBSCRIBE" -> handleSubscribe(request, output)
            "UNSUBSCRIBE" -> sendResponse(
                output, "200 OK",
                mapOf("Server" to config.server), ByteArray(0)
            )
            else -> sendResponse(
                output, "405 Method Not Allowed",
                mapOf("Content-Type" to "text/plain; charset=\"utf-8\""),
                "method not allowed".toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun handleGet(request: HttpRequest, output: OutputStream) {
        val headOnly = request.method == "HEAD"
        if (request.path == "/description.xml") {
            sendResponse(
                output, "200 OK",
                mapOf(
                    "Content-Type" to "text/xml; charset=\"utf-8\"",
                    "Server" to config.server
                ),
                description.toByteArray(Charsets.UTF_8), headOnly
            )
            return
        }

        val scpdMatch = Regex("^/dlna/(AVTransport|RenderingControl|ConnectionManager)\\.xml$")
            .find(request.path)
        if (scpdMatch != null) {
            val body = scpd[scpdMatch.groupValues[1]] ?: ""
            sendResponse(
                output, "200 OK",
                mapOf(
                    "Content-Type" to "text/xml; charset=\"utf-8\"",
                    "Server" to config.server
                ),
                body.toByteArray(Charsets.UTF_8), headOnly
            )
            return
        }

        if (request.path == "/") {
            sendResponse(
                output, "200 OK",
                mapOf("Content-Type" to "text/plain; charset=\"utf-8\""),
                "Vcast DLNA renderer\n".toByteArray(Charsets.UTF_8), headOnly
            )
            return
        }

        sendResponse(
            output, "404 Not Found",
            mapOf("Content-Type" to "text/plain; charset=\"utf-8\""),
            "not found".toByteArray(Charsets.UTF_8), headOnly
        )
    }

    private fun handlePost(request: HttpRequest, output: OutputStream) {
        val match = Regex("^/(AVTransport|RenderingControl|ConnectionManager)/action$")
            .find(request.path)
        if (match == null) {
            sendResponse(
                output, "404 Not Found",
                mapOf("Content-Type" to "text/plain; charset=\"utf-8\""),
                "not found".toByteArray(Charsets.UTF_8)
            )
            return
        }

        val service = match.groupValues[1]
        val body = String(request.body, Charsets.UTF_8)
        val action = parseAction(request.headers["soapaction"], body)
        val args = extractArgs(body)
        val outputArgs = handleAction(service, action, args, state)
        log("[soap] $service#$action $args")
        val xml = buildSoapResponse(service, action, outputArgs)
        sendResponse(
            output, "200 OK",
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "Server" to config.server,
                "EXT" to ""
            ),
            xml.toByteArray(Charsets.UTF_8)
        )
    }

    private fun handleSubscribe(request: HttpRequest, output: OutputStream) {
        val sid = request.headers["sid"] ?: "uuid:${UUID.randomUUID()}"
        val timeout = request.headers["timeout"] ?: "Second-1800"
        val callback = request.headers["callback"]
        val eventPath = Regex("^/(AVTransport|RenderingControl|ConnectionManager)/event$")
            .find(request.path)

        log("[event] SUBSCRIBE ${request.path}")
        if (callback != null && eventPath != null) {
            val service = eventPath.groupValues[1]
            notifier.execute { sendInitialEvent(callback, service, state) }
        }

        sendResponse(
            output, "200 OK",
            mapOf(
                "SID" to sid,
                "TIMEOUT" to timeout,
                "Server" to config.server
            ),
            ByteArray(0)
        )
    }

    private fun sendResponse(
        output: OutputStream,
        status: String,
        headers: Map<String, String>,
        body: ByteArray,
        headOnly: Boolean = false
    ) {
        val allHeaders = LinkedHashMap(headers)
        if (!allHeaders.containsKey("Content-Type")) {
            allHeaders["Content-Type"] = "text/xml; charset=\"utf-8\""
        }
        allHeaders["Content-Length"] = body.size.toString()
        allHeaders["Connection"] = "close"

        val builder = StringBuilder("HTTP/1.1 $status\r\n")
        for ((key, value) in allHeaders) {
            builder.append(key).append(": ").append(value).append("\r\n")
        }
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun buildDescription(cfg: VcastConfig): String {
        val values = mapOf(
            "friendlyName" to cfg.friendlyName,
            "manufacturer" to cfg.manufacturer,
            "manufacturerURL" to cfg.manufacturerURL,
            "modelDescription" to cfg.modelDescription,
            "modelName" to cfg.modelName,
            "modelNumber" to cfg.modelNumber,
            "modelURL" to cfg.modelURL,
            "serialNumber" to cfg.serialNumber,
            "udn" to cfg.udn
        )
        return Regex("\\{\\{(\\w+)\\}\\}").replace(loadAsset(context, "description.xml")) { match ->
            escapeXml(values[match.groupValues[1]])
        }
    }

    private fun parseAction(soapAction: String?, body: String): String {
        if (!soapAction.isNullOrEmpty()) {
            val hash = soapAction.indexOf('#')
            if (hash >= 0) {
                return soapAction.substring(hash + 1).replace("\"", "").replace("'", "").trim()
            }
        }
        val match = Regex("<(?:\\w+:)?([A-Za-z_][\\w]*)[\\s>]").find(body)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun extractArgs(body: String): Map<String, String> {
        val args = LinkedHashMap<String, String>()
        val regex = Regex("<([A-Za-z_][\\w.-]*)(?:\\s[^>]*)?>([\\s\\S]*?)</\\1>")
        for (match in regex.findAll(body)) {
            val name = match.groupValues[1]
            if (!args.containsKey(name)) {
                args[name] = decodeXml(match.groupValues[2])
            }
        }
        return args
    }

    private fun handleAction(
        service: String,
        action: String,
        args: Map<String, String>,
        state: TransportState
    ): Map<String, String> {
        fun arg(name: String): String = args[name] ?: ""
        val output = LinkedHashMap<String, String>()
        val duration = "00:00:00"

        when ("$service#$action") {
            "AVTransport#SetAVTransportURI" ->
                state.setUri(arg("CurrentURI"), arg("CurrentURIMetaData"))
            "AVTransport#Play" -> state.play()
            "AVTransport#Pause" -> state.pause()
            "AVTransport#Stop" -> state.stop()
            "AVTransport#GetTransportInfo" -> {
                output["CurrentTransportState"] = state.transport
                output["CurrentTransportStatus"] = state.status
                output["CurrentSpeed"] = "1"
            }
            "AVTransport#GetMediaInfo" -> {
                output["NrTracks"] = if (state.uri.isNotEmpty()) "1" else "0"
                output["MediaDuration"] = duration
                output["CurrentURI"] = state.uri
                output["CurrentURIMetaData"] = state.metadata
                output["NextURI"] = ""
                output["NextURIMetaData"] = ""
                output["PlayMedium"] = "NETWORK"
                output["RecordMedium"] = "NOT_IMPLEMENTED"
                output["WriteStatus"] = "NOT_IMPLEMENTED"
            }
            "AVTransport#GetPositionInfo" -> {
                output["Track"] = if (state.uri.isNotEmpty()) "1" else "0"
                output["TrackDuration"] = duration
                output["TrackMetaData"] = state.metadata
                output["TrackURI"] = state.uri
                output["RelTime"] = state.positionHms()
                output["AbsTime"] = state.positionHms()
                output["RelCount"] = "2147483647"
                output["AbsCount"] = "2147483647"
            }
            "AVTransport#GetTransportSettings" -> {
                output["PlayMode"] = "NORMAL"
                output["RecQualityMode"] = "NOT_IMPLEMENTED"
            }
            "AVTransport#GetDeviceCapabilities" -> {
                output["PlayMedia"] = "NETWORK,HDD,UNKNOWN"
                output["RecMedia"] = "NOT_IMPLEMENTED"
                output["RecQualityModes"] = "NOT_IMPLEMENTED"
            }
            "AVTransport#GetCurrentTransportActions" ->
                output["Actions"] = "Play,Stop,Pause,Seek"
            "RenderingControl#GetVolume" ->
                output["CurrentVolume"] = state.volume.toString()
            "RenderingControl#SetVolume" -> {
                val value = arg("DesiredVolume").toIntOrNull()
                if (value != null) state.updateVolume(value)
            }
            "RenderingControl#GetMute" ->
                output["CurrentMute"] = if (state.mute) "1" else "0"
            "RenderingControl#SetMute" -> {
                val value = arg("DesiredMute")
                state.mute = !(value == "0" || value.equals("false", true) || value.equals("no", true))
            }
            "RenderingControl#ListPresets" ->
                output["CurrentPresetNameList"] = "FactoryDefaults"
            "RenderingControl#GetVolumeDB" ->
                output["CurrentVolume"] = "0"
            "RenderingControl#GetVolumeDBRange" -> {
                output["MinValue"] = "-32767"
                output["MaxValue"] = "32767"
            }
            "ConnectionManager#GetProtocolInfo" -> {
                output["Source"] = ""
                output["Sink"] = SINK_PROTOCOL_INFO
            }
            "ConnectionManager#GetCurrentConnectionIDs" ->
                output["ConnectionIDs"] = "0"
            "ConnectionManager#GetCurrentConnectionInfo" -> {
                output["RcsID"] = "0"
                output["AVTransportID"] = "0"
                output["ProtocolInfo"] = ""
                output["PeerConnectionManager"] = ""
                output["PeerConnectionID"] = "-1"
                output["Direction"] = "Output"
                output["Status"] = "OK"
            }
        }
        return output
    }

    private fun buildSoapResponse(
        service: String,
        action: String,
        outputArgs: Map<String, String>
    ): String {
        val namespace = SERVICE_TYPES[service] ?: ""
        val args = outputArgs.entries.joinToString("") { (key, value) ->
            "<$key>${escapeXml(value)}</$key>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action" + "Response xmlns:u=\"$namespace\">$args" +
            "</u:$action" + "Response></s:Body></s:Envelope>"
    }

    private fun sendInitialEvent(callback: String, service: String, state: TransportState) {
        try {
            val match = Regex("<([^>]+)>").find(callback) ?: return
            val url = URL(match.groupValues[1])

            val properties = when (service) {
                "AVTransport" -> {
                    val lastChange = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">" +
                        "<InstanceID val=\"0\">" +
                        "<TransportState val=\"${state.transport}\"/>" +
                        "<TransportStatus val=\"${state.status}\"/>" +
                        "</InstanceID></Event>"
                    "<LastChange>${escapeXml(lastChange)}</LastChange>"
                }
                "RenderingControl" -> {
                    val lastChange = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">" +
                        "<InstanceID val=\"0\">" +
                        "<Volume channel=\"Master\" val=\"${state.volume}\"/>" +
                        "<Mute channel=\"Master\" val=\"${if (state.mute) "1" else "0"}\"/>" +
                        "</InstanceID></Event>"
                    "<LastChange>${escapeXml(lastChange)}</LastChange>"
                }
                else -> "<SourceProtocolInfo></SourceProtocolInfo>" +
                    "<SinkProtocolInfo>${escapeXml(SINK_PROTOCOL_INFO)}</SinkProtocolInfo>" +
                    "<CurrentConnectionIDs>0</CurrentConnectionIDs>"
            }

            val body = "<?xml version=\"1.0\"?>" +
                "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
                "<e:property>$properties</e:property></e:propertyset>"

            sendNotify(url, body)
        } catch (e: Exception) {
            log("[event] initial NOTIFY failed: ${e.message}")
        }
    }

    private fun sendNotify(url: URL, body: String) {
        val port = if (url.port != -1) url.port else 80
        val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
        val payload = body.toByteArray(Charsets.UTF_8)
        val host = if (url.port != -1) "${url.host}:${url.port}" else url.host
        val request = "NOTIFY $path HTTP/1.1\r\n" +
            "HOST: $host\r\n" +
            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "NT: upnp:event\r\n" +
            "NTS: upnp:propchange\r\n" +
            "SEQ: 0\r\n" +
            "Connection: close\r\n\r\n"

        Socket().use { socket ->
            socket.connect(InetSocketAddress(url.host, port), 5_000)
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                write(payload)
                flush()
            }
        }
    }
}
