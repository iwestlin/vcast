package com.viegg.vcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var addrText: TextView
    private lateinit var lastCastText: TextView
    private lateinit var logText: TextView
    private lateinit var nameInput: EditText
    private lateinit var keywordInput: EditText
    private lateinit var endpointInput: EditText
    private lateinit var passkeyInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val stateListener: (AppState.Snapshot) -> Unit = { snapshot ->
        render(snapshot)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addrText = findViewById(R.id.addrText)
        lastCastText = findViewById(R.id.lastCastText)
        logText = findViewById(R.id.logText)
        nameInput = findViewById(R.id.nameInput)
        keywordInput = findViewById(R.id.keywordInput)
        endpointInput = findViewById(R.id.endpointInput)
        passkeyInput = findViewById(R.id.passkeyInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        nameInput.setText(Settings.friendlyName(this))
        keywordInput.setText(Settings.taskKeyword(this))
        endpointInput.setText(Settings.taskEndpoint(this))
        passkeyInput.setText(Settings.passkey(this))

        findViewById<Button>(R.id.startButton).setOnClickListener {
            saveSettings(showConfirmation = false)
            requestNotificationPermission()
            startForegroundService(
                Intent(this, VcastService::class.java)
                    .setAction(VcastService.ACTION_START)
            )
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, VcastService::class.java))
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveSettings(showConfirmation = true)
        }

        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            AppState.clearLog()
        }

        render(AppState.snapshot())
    }

    override fun onStart() {
        super.onStart()
        AppState.addListener(stateListener)
    }

    override fun onStop() {
        AppState.removeListener(stateListener)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST && grantResults.any {
                it != PackageManager.PERMISSION_GRANTED
            }
        ) {
            Toast.makeText(
                this,
                R.string.notification_permission_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveSettings(showConfirmation: Boolean) {
        Settings.save(
            this,
            nameInput.text.toString(),
            keywordInput.text.toString(),
            endpointInput.text.toString(),
            passkeyInput.text.toString()
        )
        if (showConfirmation) {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_REQUEST
            )
        }
    }

    private fun render(snapshot: AppState.Snapshot) {
        statusText.text = getString(
            if (snapshot.running) R.string.status_running else R.string.status_stopped
        )

        addrText.text = if (snapshot.running) {
            "${snapshot.name}\n${snapshot.ip}:${snapshot.port}\nUDN: ${snapshot.udn}"
        } else {
            getString(R.string.stopped_hint)
        }

        lastCastText.text = if (snapshot.lastUrl.isNotBlank()) {
            val title = snapshot.lastTitle
            val label = if (title.isBlank()) snapshot.lastUrl else "$title\n${snapshot.lastUrl}"
            getString(R.string.last_cast, label)
        } else {
            ""
        }

        logText.text = snapshot.log.joinToString("\n")
        startButton.isEnabled = !snapshot.running
        stopButton.isEnabled = snapshot.running
    }

    private companion object {
        const val NOTIFICATION_REQUEST = 1
    }
}
