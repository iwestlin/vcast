package com.viegg.vcast

class TransportState {
    @Volatile var uri: String = ""
        private set
    @Volatile var metadata: String = ""
        private set
    @Volatile var transport: String = "NO_MEDIA_PRESENT"
        private set
    @Volatile var status: String = "OK"
        private set
    @Volatile var volume: Int = 100
    @Volatile var mute: Boolean = false

    @Volatile private var startedAt: Long? = null
    @Volatile private var offset: Double = 0.0

    var onUri: ((String, String, String) -> Unit)? = null

    fun setUri(newUri: String?, newMetadata: String?) {
        val callback: ((String, String, String) -> Unit)?
        val notifyUri: String
        val notifyTitle: String
        val notifyMetadata: String
        synchronized(this) {
            uri = newUri ?: ""
            metadata = newMetadata ?: ""
            transport = "PAUSED_PLAYBACK"
            status = "OK"
            startedAt = null
            offset = 0.0
            callback = onUri
            notifyUri = uri
            notifyTitle = parseTitle(metadata)
            notifyMetadata = metadata
        }
        callback?.invoke(notifyUri, notifyTitle, notifyMetadata)
    }

    fun play() {
        synchronized(this) {
            if (transport != "PLAYING") {
                startedAt = System.currentTimeMillis()
                transport = "PLAYING"
            }
            status = "OK"
        }
    }

    fun pause() {
        synchronized(this) {
            if (transport == "PLAYING") {
                offset = positionSecondsLocked()
                startedAt = null
            }
            transport = "PAUSED_PLAYBACK"
        }
    }

    fun stop() {
        synchronized(this) {
            transport = "STOPPED"
            startedAt = null
            offset = 0.0
        }
    }

    fun updateVolume(value: Int) {
        volume = value.coerceIn(0, 100)
    }

    private fun positionSecondsLocked(): Double {
        val start = startedAt
        return if (transport == "PLAYING" && start != null) {
            offset + (System.currentTimeMillis() - start) / 1000.0
        } else {
            offset
        }
    }

    fun positionSeconds(): Double = synchronized(this) { positionSecondsLocked() }

    fun positionHms(): String = toHms(positionSeconds())
}
