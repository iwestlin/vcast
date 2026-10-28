package com.viegg.vcast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var addrText: TextView
    private lateinit var lastCastText: TextView
    private lateinit var lastCastUrlText: TextView
    private lateinit var copyLastCastButton: Button
    private lateinit var logText: TextView
    private lateinit var nameInput: EditText
    private lateinit var keywordInput: EditText
    private lateinit var endpointInput: EditText
    private lateinit var passkeyInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var languageSpinner: Spinner

    private val stateListener: (AppState.Snapshot) -> Unit = { snapshot ->
        render(snapshot)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addrText = findViewById(R.id.addrText)
        lastCastText = findViewById(R.id.lastCastText)
        lastCastUrlText = findViewById(R.id.lastCastUrlText)
        copyLastCastButton = findViewById(R.id.copyLastCastButton)
        logText = findViewById(R.id.logText)
        nameInput = findViewById(R.id.nameInput)
        keywordInput = findViewById(R.id.keywordInput)
        endpointInput = findViewById(R.id.endpointInput)
        passkeyInput = findViewById(R.id.passkeyInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        languageSpinner = findViewById(R.id.languageSpinner)

        setupLanguageSpinner()
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

        copyLastCastButton.setOnClickListener {
            copyLastCastLink()
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

    private fun setupLanguageSpinner() {
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_chinese)
        )
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        languageSpinner.setSelection(languagePosition(AppLocale.current(this)))
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val language = languageTag(position)
                if (language != AppLocale.current(this@MainActivity)) {
                    AppLocale.set(this@MainActivity, language)
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun languagePosition(language: String): Int = when (language) {
        AppLocale.ENGLISH -> 1
        AppLocale.CHINESE -> 2
        else -> 0
    }

    private fun languageTag(position: Int): String = when (position) {
        1 -> AppLocale.ENGLISH
        2 -> AppLocale.CHINESE
        else -> AppLocale.SYSTEM
    }

    private fun copyLastCastLink() {
        val url = AppState.snapshot().lastUrl
        if (url.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.cast_link), url)
        )
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
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
            getString(
                R.string.device_info,
                snapshot.name,
                "${snapshot.ip}:${snapshot.port}",
                snapshot.udn
            )
        } else {
            getString(R.string.stopped_hint)
        }

        val lastUrl = snapshot.lastUrl
        if (lastUrl.isBlank()) {
            lastCastText.text = ""
            lastCastText.visibility = View.GONE
            lastCastUrlText.text = ""
            lastCastUrlText.visibility = View.GONE
            copyLastCastButton.visibility = View.GONE
        } else {
            lastCastText.text = getString(
                R.string.last_cast,
                snapshot.lastTitle.ifBlank { lastUrl }
            )
            lastCastText.visibility = View.VISIBLE
            lastCastUrlText.text = if (snapshot.lastTitle.isBlank()) "" else lastUrl
            lastCastUrlText.visibility =
                if (lastCastUrlText.text.isBlank()) View.GONE else View.VISIBLE
            copyLastCastButton.visibility = View.VISIBLE
        }

        renderLog(snapshot.log)
        startButton.isEnabled = !snapshot.running
        stopButton.isEnabled = snapshot.running
    }

    private fun renderLog(lines: List<String>) {
        val text = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = text.length
            text.append(line)
            val color = logColor(line)
            if (color != 0) {
                text.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (index < lines.lastIndex) text.append('\n')
        }
        logText.text = text
    }

    private fun logColor(line: String): Int {
        val body = line.replaceFirst(LOG_TIMESTAMP, "")
        val lower = body.lowercase(Locale.US)
        if (body.startsWith("[cast]")) {
            return getColor(R.color.log_cast)
        }
        if (body.startsWith("[task]")) {
            val status = HTTP_STATUS.find(body)?.groupValues?.get(1)?.toIntOrNull()
            if (status != null) {
                return getColor(
                    if (status in 200..299) R.color.log_success else R.color.log_error
                )
            }
            if (lower.contains("submitting") || lower.contains("提交")) {
                return getColor(R.color.log_task)
            }
            if (isErrorLog(lower)) {
                return getColor(R.color.log_error)
            }
            return getColor(R.color.log_task)
        }
        if (isErrorLog(lower)) {
            return getColor(R.color.log_error)
        }
        return 0
    }

    private fun isErrorLog(lower: String): Boolean =
        lower.contains("error") ||
            lower.contains("failed") ||
            lower.contains("invalid") ||
            lower.contains("失败") ||
            lower.contains("错误") ||
            lower.contains("无效")

    private companion object {
        const val NOTIFICATION_REQUEST = 1
        val LOG_TIMESTAMP = Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\] ")
        val HTTP_STATUS = Regex("HTTP (\\d{3})")
    }
}
