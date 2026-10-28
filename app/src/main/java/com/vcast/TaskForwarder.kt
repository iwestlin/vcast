package com.vcast

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TaskResult(val status: Int, val body: String)

object TaskForwarder {
    fun matchesKeyword(url: String, keyword: String): Boolean {
        val pattern = keyword.trim()
        if (pattern.isEmpty()) return false
        if (pattern == "*") return true
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    private val TEXT_FIELDS = listOf(
        "creator" to "dc:creator",
        "artist" to "upnp:artist",
        "album" to "upnp:album",
        "genre" to "upnp:genre",
        "albumArtURI" to "upnp:albumArtURI",
        "author" to "upnp:author",
        "actor" to "upnp:actor",
        "director" to "upnp:director",
        "date" to "dc:date",
        "class" to "upnp:class",
        "description" to "dc:description",
        "longDescription" to "upnp:longDescription",
        "rating" to "upnp:rating"
    )

    private val RES_FIELDS = listOf(
        "protocolInfo" to "protocolInfo",
        "duration" to "duration",
        "size" to "size",
        "bitrate" to "bitrate",
        "resolution" to "resolution",
        "sampleFrequency" to "sampleFrequency",
        "bitsPerSample" to "bitsPerSample",
        "nrAudioChannels" to "nrAudioChannels"
    )

    private fun buildExtra(metadata: String): JSONObject {
        val extra = JSONObject()
        if (metadata.isBlank()) return extra
        extra.put("metadata", metadata)

        for ((key, tag) in TEXT_FIELDS) {
            val value = firstTag(metadata, tag) ?: continue
            extra.put(key, value)
        }

        val resAttributes = Regex("<res\\b([^>]*)>", RegexOption.IGNORE_CASE)
            .find(metadata)?.groupValues?.get(1)
        if (resAttributes != null) {
            for ((key, name) in RES_FIELDS) {
                val regex = Regex(
                    "\\b" + Regex.escape(name) + "\\s*=\\s*[\"']([^\"']*)[\"']",
                    RegexOption.IGNORE_CASE
                )
                val value = regex.find(resAttributes)?.groupValues?.get(1)
                if (!value.isNullOrBlank()) extra.put(key, decodeXml(value))
            }
        }
        return extra
    }

    private fun firstTag(metadata: String, tag: String): String? {
        val regex = Regex(
            "<" + Regex.escape(tag) + "(?:\\s[^>]*)?>([\\s\\S]*?)</" + Regex.escape(tag) + ">",
            RegexOption.IGNORE_CASE
        )
        val value = regex.find(metadata)?.groupValues?.get(1) ?: return null
        return decodeXml(value).trim().ifBlank { null }
    }

    fun addTask(
        room: String,
        title: String,
        metadata: String,
        endpoint: String,
        passkey: String,
        callback: (Result<TaskResult>) -> Unit
    ) {
        Thread({
            try {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                val request = JSONObject()
                    .put("room", room)
                    .put("passkey", passkey)
                if (title.isNotBlank()) request.put("title", title)
                val extra = runCatching { buildExtra(metadata) }.getOrElse { JSONObject() }
                if (extra.length() > 0) request.put("extra", extra)
                val payload = request.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)

                connection.outputStream.use { it.write(payload) }
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                connection.disconnect()
                callback(Result.success(TaskResult(status, body)))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }, "task-forwarder").apply { isDaemon = true }.start()
    }
}
