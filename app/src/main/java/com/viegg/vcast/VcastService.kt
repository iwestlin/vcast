package com.viegg.vcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VcastService : Service() {
    companion object {
        const val ACTION_START = "com.viegg.vcast.START"
        const val ACTION_STOP = "com.viegg.vcast.STOP"

        private const val TAG = "Vcast"
        private const val CHANNEL_ID = "vcast"
        private const val NOTIFICATION_ID = 1
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vcast-main").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)

    @Volatile private var running = false
    private var httpServer: HttpServer? = null
    private var ssdpServer: SsdpServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startEverything()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopEverything()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startEverything() {
        if (running) {
            updateNotification("Running")
            return
        }
        running = true
        val startingNotification = buildNotification("Starting...")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    startingNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, startingNotification)
            }
        } catch (e: Exception) {
            running = false
            log("[main] foreground start failed: ${e.message}")
            AppState.setStopped()
            stopSelf()
            return
        }
        acquireLocks()

        val myGeneration = generation.incrementAndGet()
        if (!running) {
            stopSelf()
            return
        }
        worker.execute {
            var server: HttpServer? = null
            var ssdp: SsdpServer? = null
            try {
                val config = Config.resolve(applicationContext)
                val state = TransportState()
                state.onUri = { uri, title, metadata -> onCast(uri, title, metadata, config) }

                val http = HttpServer(applicationContext, config, state) { line -> log(line) }
                server = http
                http.start()
                if (generation.get() != myGeneration) {
                    http.stop()
                    return@execute
                }

                val location = "http://${config.ip}:${http.port}/description.xml"
                val ssdpInstance = SsdpServer(config, location) { line -> log(line) }
                ssdp = ssdpInstance
                ssdpInstance.start()
                if (!running || generation.get() != myGeneration) {
                    ssdpInstance.stop()
                    http.stop()
                    return@execute
                }

                httpServer = http
                ssdpServer = ssdpInstance
                AppState.setRunning(config.friendlyName, config.ip, http.port, config.udn)
                if (!running || generation.get() != myGeneration) {
                    AppState.setStopped()
                    ssdpInstance.stop()
                    http.stop()
                    if (httpServer === http) httpServer = null
                    if (ssdpServer === ssdpInstance) ssdpServer = null
                    return@execute
                }
                log("[main] Vcast running at ${config.ip}:${http.port}")
                log("[main] device: ${config.friendlyName}")
                log("[main] UDN: uuid:${config.udn}")
                if (config.ip == "127.0.0.1") {
                    log("[main] no Wi-Fi/LAN address found; connect Wi-Fi or hotspot")
                }
                if (config.taskEndpoint.isBlank()) {
                    log("[main] task endpoint not set; forwarding disabled")
                } else if (config.taskKeyword.isBlank()) {
                    log("[main] task keyword not set; forwarding disabled")
                } else {
                    val error = runCatching {
                        TaskForwarder.matchesKeyword("", config.taskKeyword)
                    }.exceptionOrNull()
                    if (error == null) {
                        log("[main] forwarding enabled (keyword: ${config.taskKeyword})")
                    } else {
                        log("[main] invalid task keyword regex: ${error.message}; forwarding disabled")
                    }
                }
                updateNotification("Running · ${config.ip}:${http.port}")
            } catch (e: Exception) {
                log("[main] fatal: ${e.message}")
                ssdp?.stop()
                server?.stop()
                AppState.setStopped()
                stopEverything()
                stopSelf()
            }
        }
    }

    private fun stopEverything() {
        val wasRunning = running
        running = false
        generation.incrementAndGet()
        ssdpServer?.stop()
        ssdpServer = null
        httpServer?.stop()
        httpServer = null
        releaseLocks()
        if (wasRunning) {
            log("[main] stopped")
        }
        AppState.setStopped()
    }

    private fun onCast(uri: String, title: String, metadata: String, config: VcastConfig) {
        if (!running || uri.isBlank()) return
        log("[cast] ${title.ifBlank { "(no title)" }} -> $uri")
        AppState.setCast(uri, title)
        updateNotification(if (title.isBlank()) "Cast: $uri" else "Cast: $title")

        val keyword = config.taskKeyword.trim()
        if (keyword.isEmpty()) return
        val matched = try {
            TaskForwarder.matchesKeyword(uri, keyword)
        } catch (e: Exception) {
            log("[task] invalid keyword regex: ${e.message}")
            return
        }
        if (!matched) return
        if (config.taskEndpoint.isBlank()) {
            log("[task] keyword matched, but task endpoint is not set; skipped")
            return
        }
        log("[task] keyword matched, submitting to ${config.taskEndpoint}")
        TaskForwarder.addTask(uri, title, metadata, config.taskEndpoint, config.passkey) { result ->
            result.onSuccess { response ->
                log("[task] response HTTP ${response.status}: ${response.body.ifBlank { "(empty)" }}")
            }.onFailure { error ->
                log("[task] response error: ${error.message ?: error.toString()}")
            }
        }
    }

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("vcast:multicast").apply {
                setReferenceCounted(false)
                acquire()
            }
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "vcast:wifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            log("[main] multicast/wifi lock acquired")
        } catch (e: Exception) {
            log("[main] wifi lock failed: ${e.message}")
        }

        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "vcast:cpu"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            log("[main] wake lock failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        multicastLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        multicastLock = null
        wifiLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wifiLock = null
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        AppState.log(line)
    }

    private fun updateNotification(text: String) {
        if (!running) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VcastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Vcast")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Stop",
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DLNA renderer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the DLNA renderer running in the background"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
