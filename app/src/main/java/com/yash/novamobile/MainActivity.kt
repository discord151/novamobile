package com.yash.novamobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply { text = "Nova Mobile"; textSize = 28f })
        status = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 32) }
        root.addView(status)

        fun btn(label: String, onClick: () -> Unit) =
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })

        btn("Start") { start(null) }
        btn("Learn my earbud triple-click") { start(ListenerService.ACTION_LEARN) }
        btn("Stop") {
            startService(Intent(this, ListenerService::class.java).setAction(ListenerService.ACTION_STOP))
            status.postDelayed({ refresh() }, 400)
        }
        btn("Allow background running (battery)") { batteryExemption() }
        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val key = getSharedPreferences(ListenerService.PREFS, MODE_PRIVATE)
            .getInt(ListenerService.KEY_TRIGGER, ListenerService.DEFAULT_TRIGGER)
        status.text = (if (ListenerService.running)
            "Running. Triple-click your earbuds, wait for the beep, then say \"call <name>\"."
        else "Not running.") + "\nTrigger button: " + KeyEvent.keyCodeToString(key)
    }

    private var pendingAction: String? = null

    private fun start(action: String?) {
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pendingAction = action
            requestPermissions(perms, 1); return
        }
        // Must be started while the app is on screen (Android 14 microphone-FGS rule)
        startForegroundService(Intent(this, ListenerService::class.java).apply { this.action = action })
        status.postDelayed({ refresh() }, 600)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        // CALL_PHONE, RECORD_AUDIO and READ_CONTACTS are the essential three
        val essentials = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (essentials.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) start(pendingAction)
        else status.text = "Mic, contacts and phone permissions are required."
    }

    private fun batteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }
}
