package com.viegg.vcast

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppState {
    data class Snapshot(
        val running: Boolean = false,
        val name: String = "",
        val ip: String = "",
        val port: Int = 0,
        val udn: String = "",
        val lastUrl: String = "",
        val lastTitle: String = "",
        val log: List<String> = emptyList()
    )

    private const val MAX_LOG = 200
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var current = Snapshot()
    private val lines = ArrayDeque<String>()

    fun snapshot(): Snapshot = synchronized(lock) { current }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        postToMain { listener(snapshot()) }
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun log(line: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lock) {
            lines.addLast("[$timestamp] $line")
            while (lines.size > MAX_LOG) lines.removeFirst()
            current = current.copy(log = lines.toList())
        }
        notifyListeners()
    }

    fun setRunning(name: String, ip: String, port: Int, udn: String) {
        synchronized(lock) {
            current = current.copy(
                running = true,
                name = name,
                ip = ip,
                port = port,
                udn = udn
            )
        }
        notifyListeners()
    }

    fun setStopped() {
        synchronized(lock) { current = current.copy(running = false) }
        notifyListeners()
    }

    fun setCast(url: String, title: String) {
        synchronized(lock) { current = current.copy(lastUrl = url, lastTitle = title) }
        notifyListeners()
    }

    fun clearLog() {
        synchronized(lock) {
            lines.clear()
            current = current.copy(log = emptyList())
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val snap = snapshot()
        postToMain { listeners.forEach { it(snap) } }
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post(action)
        }
    }
}
