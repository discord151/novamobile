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
import android.text.InputType
import android.view.KeyEvent
import android.widget.*

class MainActivity : Activity() {

    private val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private lateinit var status: TextView
    private lateinit var aliases: EditText
    private val prefs get() = getSharedPreferences(ListenerService.PREFS, MODE_PRIVATE)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
        }
        root.addView(TextView(this).apply { text = "Nova Mobile"; textSize = 28f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 24) }
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

        root.addView(TextView(this).apply { text = "\nUnlock"; textSize = 20f })
        btn("1. Open accessibility settings (turn on Nova Mobile unlock)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val pinBox = EditText(this).apply {
            hint = "Phone PIN (digits only)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        root.addView(pinBox)
        btn("2. Save PIN (encrypted on this phone)") {
            val pin = pinBox.text.toString()
            if (pin.length < 4) Toast.makeText(this, "Enter your PIN first", Toast.LENGTH_SHORT).show()
            else { PinStore.save(this, pin); pinBox.setText(""); refresh()
                Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show() }
        }

        root.addView(TextView(this).apply {
            text = "\nName fixes (one per line:  what-it-heard=Contact name)"; textSize = 16f
        })
        aliases = EditText(this).apply {
            setText(prefs.getString(ContactResolver.KEY_ALIASES, ""))
            hint = "rule=Rahul"
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(aliases)
        btn("Save name fixes") {
            prefs.edit().putString(ContactResolver.KEY_ALIASES, aliases.text.toString()).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val key = prefs.getInt(ListenerService.KEY_TRIGGER, ListenerService.DEFAULT_TRIGGER)
        status.text = buildString {
            append(if (ListenerService.running)
                "Running. Triple-click earbuds, wait for the beep, then say \"call <name>\", \"unlock\" or \"open <app>\"."
            else "Not running.")
            append("\nTrigger button: ").append(KeyEvent.keyCodeToString(key))
            append("\nUnlock service: ").append(if (UnlockService.instance != null) "ON" else "OFF")
            append("   PIN: ").append(if (PinStore.has(this@MainActivity)) "saved" else "not saved")
            append("\nLast heard: ").append(prefs.getString("last_heard", "-"))
        }
    }

    private var pendingAction: String? = null

    private fun start(action: String?) {
        if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            pendingAction = action
            requestPermissions(perms, 1); return
        }
        startForegroundService(Intent(this, ListenerService::class.java).apply { this.action = action })
        status.postDelayed({ refresh() }, 600)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
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
