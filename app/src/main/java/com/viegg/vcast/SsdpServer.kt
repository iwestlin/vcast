package com.viegg.vcast

import android.content.Context
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val SSDP_ADDR = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MAX_AGE = 66
private const val ALIVE_INTERVAL_MS = 20_000L

private data class SsdpTarget(val st: String, val usn: String)

class SsdpServer(
    private val context: Context,
    private val config: VcastConfig,
    private val location: String,
    private val log: (String) -> Unit
) {
    private val targets: List<SsdpTarget> = buildTargets(config.udn)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ssdp-timer").apply { isDaemon = true }
        }

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var receiver: Thread? = null

    fun start() {
        val localAddress = InetAddress.getByName(config.ip)
        val iface = NetworkInterface.getByInetAddress(localAddress)
        val udp = MulticastSocket(null)
        udp.reuseAddress = true
        udp.bind(InetSocketAddress(SSDP_PORT))
        udp.timeToLive = 4
        try {
            if (iface != null) {
                udp.networkInterface = iface
                udp.joinGroup(
                    InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT),
                    iface
                )
                log(
                    context.getString(
                        R.string.log_ssdp_joined,
                        SSDP_ADDR,
                        iface.name,
                        config.ip
                    )
                )
            } else {
                udp.joinGroup(InetAddress.getByName(SSDP_ADDR))
                log(context.getString(R.string.log_ssdp_joined_default, SSDP_ADDR))
            }
            udp.loopbackMode = true
        } catch (e: Exception) {
            log(
                context.getString(
                    R.string.log_ssdp_setup_failed,
                    e.message ?: e.toString()
                )
            )
        }
        socket = udp
        running = true

        receiver = Thread({ receiveLoop() }, "ssdp-receive").apply {
            isDaemon = true
            start()
        }

        announceAlive()
        scheduler.scheduleAtFixedRate(
            { if (running) runCatching { announceAlive() } },
            ALIVE_INTERVAL_MS,
            ALIVE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        log(context.getString(R.string.log_ssdp_bound, SSDP_PORT))
    }

    fun stop() {
        if (!running && socket == null) return
        running = false
        runCatching { announceByeBye() }
        scheduler.shutdownNow()
        runCatching { socket?.close() }
        socket = null
        receiver = null
    }

    private fun receiveLoop() {
        val buffer = ByteArray(8192)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                handleMessage(text, packet.address, packet.port)
            } catch (e: Exception) {
                if (running) {
                    log(
                        context.getString(
                            R.string.log_ssdp_receive_error,
                            e.message ?: e.toString()
                        )
                    )
                }
            }
        }
    }

    private fun handleMessage(text: String, address: InetAddress, port: Int) {
        val lines = text.split(Regex("\\r\\n|\\n"))
        if (lines.isEmpty() || !Regex("^M-SEARCH\\b", RegexOption.IGNORE_CASE).containsMatchIn(lines[0])) {
            return
        }

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase(Locale.US)] =
                    line.substring(index + 1).trim()
            }
        }

        val st = headers["st"]?.trim().orEmpty()
        if (st.isEmpty()) return

        val mx = (headers["mx"]?.toIntOrNull() ?: 1).coerceIn(1, 3)
        val requested = st.lowercase(Locale.US)
        val matched = if (requested == "ssdp:all") {
            targets
        } else {
            targets.filter { it.st.lowercase(Locale.US) == requested }
        }

        for (target in matched) {
            val delay = (Math.random() * minOf(mx * 1000, 400)).toLong()
            scheduler.schedule({
                if (running) {
                    log(
                        context.getString(
                            R.string.log_ssdp_search,
                            address.hostAddress,
                            st,
                            target.st
                        )
                    )
                    send(buildSearchResponse(target), port, address)
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun announceAlive() {
        for (target in targets) {
            send(buildNotify("ssdp:alive", target), SSDP_PORT, InetAddress.getByName(SSDP_ADDR))
        }
    }

    private fun announceByeBye() {
        for (target in targets) {
            send(buildNotify("ssdp:byebye", target), SSDP_PORT, InetAddress.getByName(SSDP_ADDR))
        }
    }

    private fun send(text: String, port: Int, address: InetAddress) {
        val udp = socket ?: return
        val data = text.toByteArray(Charsets.UTF_8)
        try {
            udp.send(DatagramPacket(data, data.size, address, port))
        } catch (e: Exception) {
            log(context.getString(R.string.log_ssdp_send_error, e.message ?: e.toString()))
        }
    }

    private fun buildSearchResponse(target: SsdpTarget): String = listOf(
        "HTTP/1.1 200 OK",
        "CACHE-CONTROL: max-age=$MAX_AGE",
        "DATE: ${httpDate()}",
        "EXT:",
        "LOCATION: $location",
        "SERVER: ${config.server}",
        "ST: ${target.st}",
        "USN: ${target.usn}",
        "BOOTID.UPNP.ORG: 1",
        "CONFIGID.UPNP.ORG: 1",
        "",
        ""
    ).joinToString("\r\n")

    private fun buildNotify(nts: String, target: SsdpTarget): String {
        val lines = mutableListOf("NOTIFY * HTTP/1.1", "HOST: $SSDP_ADDR:$SSDP_PORT")
        if (nts == "ssdp:alive") {
            lines.add("CACHE-CONTROL: max-age=$MAX_AGE")
            lines.add("LOCATION: $location")
            lines.add("SERVER: ${config.server}")
        }
        lines.add("NT: ${target.st}")
        lines.add("NTS: $nts")
        lines.add("USN: ${target.usn}")
        if (nts == "ssdp:alive") {
            lines.add("BOOTID.UPNP.ORG: 1")
            lines.add("CONFIGID.UPNP.ORG: 1")
        }
        lines.add("")
        lines.add("")
        return lines.joinToString("\r\n")
    }

    private fun buildTargets(udn: String): List<SsdpTarget> {
        val uuid = "uuid:$udn"
        return listOf(
            SsdpTarget("upnp:rootdevice", "$uuid::upnp:rootdevice"),
            SsdpTarget(uuid, uuid),
            SsdpTarget(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "$uuid::urn:schemas-upnp-org:device:MediaRenderer:1"
            ),
            SsdpTarget(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "$uuid::urn:schemas-upnp-org:service:AVTransport:1"
            ),
            SsdpTarget(
                "urn:schemas-upnp-org:service:RenderingControl:1",
                "$uuid::urn:schemas-upnp-org:service:RenderingControl:1"
            ),
            SsdpTarget(
                "urn:schemas-upnp-org:service:ConnectionManager:1",
                "$uuid::urn:schemas-upnp-org:service:ConnectionManager:1"
            )
        )
    }
}
