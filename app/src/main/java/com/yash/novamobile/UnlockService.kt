package com.yash.novamobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Wakes the screen, swipes up, taps the saved PIN on the lock screen and can launch apps. */
class UnlockService : AccessibilityService() {

    companion object {
        @Volatile var instance: UnlockService? = null
        private const val TAG = "NovaMobile"
        private const val SYSTEMUI = "com.android.systemui"
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { startActivity(it) }
    }

    fun unlock(pin: String, done: (Boolean) -> Unit) {
        if (!isLocked()) { done(true); return }
        wakeScreen()
        main.postDelayed({ swipeUp() }, 700)
        main.postDelayed({ typePin(pin, 0, 0, done) }, 1700)
    }

    private fun typePin(pin: String, i: Int, attempts: Int, done: (Boolean) -> Unit) {
        if (i >= pin.length) {
            main.postDelayed({
                findById("key_enter")?.let { click(it) }
                main.postDelayed({ done(!isLocked()) }, 1500)
            }, 300)
            return
        }
        val node = findDigit(pin[i])
        if (node == null) {
            if (attempts >= 3) { Log.w(TAG, "PIN pad not found"); done(false); return }
            swipeUp()
            main.postDelayed({ typePin(pin, i, attempts + 1, done) }, 900)
            return
        }
        click(node)
        main.postDelayed({ typePin(pin, i + 1, 0, done) }, 180)
    }

    // ----- screen helpers -----
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "NovaMobile:wake"
        ).acquire(15_000)
    }

    private fun swipeUp() {
        val dm = resources.displayMetrics
        val p = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.9f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.2f)
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null
        )
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null
        )
    }

    // ----- node helpers -----
    private fun roots(): List<AccessibilityNodeInfo> {
        val l = mutableListOf<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { l.add(it) } } } catch (_: Exception) {}
        rootInActiveWindow?.let { l.add(it) }
        return l
    }

    private fun findById(shortId: String): AccessibilityNodeInfo? {
        for (r in roots()) {
            r.findAccessibilityNodeInfosByViewId("$SYSTEMUI:id/$shortId")?.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun findDigit(d: Char): AccessibilityNodeInfo? {
        findById("key$d")?.let { return it }
        val s = d.toString()
        for (r in roots()) {
            if (r.packageName?.toString() != SYSTEMUI) continue
            r.findAccessibilityNodeInfosByText(s)?.firstOrNull {
                it.text?.toString() == s || it.contentDescription?.toString() == s
            }?.let { return it }
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            cur = cur.parent
        }
        val r = Rect(); n.getBoundsInScreen(r)
        tap(r.centerX().toFloat(), r.centerY().toFloat())
    }
}
