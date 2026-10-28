package com.viegg.vcast

import android.content.Context
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date

data class VcastConfig(
    val ip: String,
    val udn: String,
    val friendlyName: String,
    val httpPort: Int,
    val version: String = "1.0",
    val server: String,
    val manufacturer: String = "Vcast",
    val manufacturerURL: String = "",
    val modelName: String = "Vcast",
    val modelDescription: String = "DLNA Media Renderer (Android)",
    val modelNumber: String = version,
    val modelURL: String = "",
    val serialNumber: String = "1024",
    val taskEndpoint: String,
    val taskKeyword: String,
    val passkey: String
)

data class LanAddress(val ip: String, val name: String, val score: Int)

object Settings {
    private const val PREFS = "vcast"
    private const val KEY_UDN = "udn"
    private const val KEY_NAME = "name"
    private const val KEY_KEYWORD = "task_keyword"
    private const val KEY_ENDPOINT = "task_endpoint"
    private const val KEY_PASSKEY = "passkey"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getUdn(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_UDN, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString(KEY_UDN, created).commit()
        return created
    }

    fun friendlyName(context: Context): String =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultName()

    fun taskKeyword(context: Context): String =
        prefs(context).getString(KEY_KEYWORD, null)?.trim().orEmpty()

    fun taskEndpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, null)?.trim().orEmpty()

    fun passkey(context: Context): String =
        prefs(context).getString(KEY_PASSKEY, null)?.trim().orEmpty()

    fun save(
        context: Context,
        name: String,
        keyword: String,
        endpoint: String,
        passkey: String
    ) {
        prefs(context).edit()
            .putString(KEY_NAME, name.trim())
            .putString(KEY_KEYWORD, keyword.trim())
            .putString(KEY_ENDPOINT, endpoint.trim())
            .putString(KEY_PASSKEY, passkey.trim())
            .apply()
    }

    private fun defaultName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return "Vcast(" + (if (model.isNotEmpty()) model else "Android") + ")"
    }
}

object Config {
    fun resolve(context: Context, httpPort: Int = 0): VcastConfig {
        val lan = pickLanIPv4()
        val ip = lan?.ip ?: "127.0.0.1"
        return VcastConfig(
            ip = ip,
            udn = Settings.getUdn(context),
            friendlyName = Settings.friendlyName(context),
            httpPort = httpPort,
            modelDescription = context.getString(R.string.model_description),
            server = "Android/${Build.VERSION.RELEASE} UPnP/1.0 Vcast/1.0",
            taskEndpoint = Settings.taskEndpoint(context),
            taskKeyword = Settings.taskKeyword(context),
            passkey = Settings.passkey(context)
        )
    }

    fun pickLanIPv4(): LanAddress? {
        var best: LanAddress? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp) continue
                val score = interfaceScore(iface.name)
                for (address in iface.inetAddresses) {
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val host = address.hostAddress ?: continue
                    if (best == null || score > best.score) {
                        best = LanAddress(host, iface.name, score)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return best
    }

    private fun interfaceScore(name: String): Int {
        val n = name.lowercase(Locale.US)
        return when {
            Regex("^(wl|wlan|wifi|ap|swlan|softap)").containsMatchIn(n) -> 30
            Regex("^(eth|en|bridge|br-)").containsMatchIn(n) -> 25
            Regex("^(rmnet|ccmni|pdp|wwan|cellular)").containsMatchIn(n) -> 0
            Regex("^(tun|tap|tailscale|zt|wg|docker|veth|lo)").containsMatchIn(n) -> 1
            else -> 10
        }
    }
}

fun loadAsset(context: Context, name: String): String =
    context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

fun escapeXml(value: String?): String =
    (value ?: "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

fun decodeXml(value: String): String =
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        .replace("&amp;", "&")

fun parseTitle(metadata: String?): String {
    if (metadata.isNullOrEmpty()) return ""
    val match = Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>", RegexOption.IGNORE_CASE)
        .find(metadata) ?: return ""
    return decodeXml(match.groupValues[1]).trim()
}

fun toHms(seconds: Double): String {
    val total = maxOf(0, seconds.toInt())
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "$h:" + m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
}

fun httpDate(): String {
    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("GMT")
    return format.format(Date())
}
