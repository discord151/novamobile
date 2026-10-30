package com.yash.novamobile

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

/**
 * Idle until you press your earbud button the "trigger" number of times (triple-click =
 * usually KEYCODE_MEDIA_PREVIOUS on OnePlus Buds). Then: beep -> listen ~8s -> place call.
 */
class ListenerService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STOP = "com.yash.novamobile.STOP"
        const val ACTION_LEARN = "com.yash.novamobile.LEARN"
        const val PREFS = "nova"
        const val KEY_TRIGGER = "trigger_key"
        const val DEFAULT_TRIGGER = KeyEvent.KEYCODE_MEDIA_PREVIOUS

        // Try to use the earbuds' mic for the command. Set false to use the phone mic.
        const val USE_BUDS_MIC = true
        // Plays inaudible audio so Android keeps sending earbud buttons to this app.
        const val KEEP_SILENT_AUDIO = true

        private const val CHANNEL = "nova_listen"
        private const val NOTIF_ID = 1
        private const val TAG = "NovaMobile"
        private const val LISTEN_MS = 8000
        @Volatile var running = false
    }

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var learning = false
    private var budsRouted = false

    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private val triggerKey get() = prefs.getInt(KEY_TRIGGER, DEFAULT_TRIGGER)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        if (!running) {
            running = true
            tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
            setupMediaSession()
            if (KEEP_SILENT_AUDIO) startSilence()
            StorageService.unpack(this, "model-en-us", "model",
                { m -> model = m },
                { e -> Log.e(TAG, "Model load failed: ${e.message}"); stopSelf() })
        }
        if (intent?.action == ACTION_LEARN) {
            learning = true
            main.postDelayed({ say("Now triple click your earbud") }, 1000)
        }
        return START_STICKY
    }

    // ---------- foreground notification ----------
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nova", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Nova Mobile")
            .setContentText("Triple-click your earbuds, then say \"call …\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    // ---------- earbud button capture ----------
    private fun setupMediaSession() {
        session = MediaSession(this, "NovaMobile").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(i: Intent): Boolean {
                    val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    if (ev == null) return false
                    Log.d(TAG, "Media key: ${KeyEvent.keyCodeToString(ev.keyCode)} action=${ev.action}")
                    if (ev.action == KeyEvent.ACTION_DOWN) main.post { onKey(ev) }
                    return true // swallow DOWN and UP so nothing else reacts
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build()
            )
            isActive = true
        }
    }

    private fun onKey(ev: KeyEvent) {
        if (learning) {
            learning = false
            prefs.edit().putInt(KEY_TRIGGER, ev.keyCode).apply()
            say("Saved. That button now starts voice commands")
            return
        }
        if (ev.keyCode == triggerKey) startCommandWindow()
        else passThrough(ev) // let music apps still get play/pause etc.
    }

    /** Hand a non-trigger key to whatever other media app is playing. */
    private fun passThrough(ev: KeyEvent) {
        val s = session ?: return
        val am = getSystemService(AudioManager::class.java)
        s.isActive = false
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, ev.keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, ev.keyCode))
        main.postDelayed({ if (running) s.isActive = true }, 500)
    }

    private fun startSilence() {
        val sr = 8000
        val bytes = ByteArray(sr * 2)
        silence = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(bytes.size)
            .setTransferMode(AudioTrack.MODE_STATIC).build().apply {
                write(bytes, 0, bytes.size)
                setLoopPoints(0, sr, -1)
                play()
            }
    }

    // ---------- the listen window ----------
    private fun startCommandWindow() {
        val m = model
        if (listening || m == null) return
        listening = true
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaMobile:listen")
            .also { wakeLock = it }.acquire(20_000)
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
        if (USE_BUDS_MIC) routeToBuds()
        main.postDelayed({
            if (!listening) return@postDelayed
            try {
                speech = SpeechService(Recognizer(m, 16000.0f), 16000.0f).also { it.startListening(this, LISTEN_MS) }
            } catch (e: Exception) {
                Log.e(TAG, "listen failed", e); endListening()
            }
        }, 500)
    }

    private fun endListening() {
        speech?.stop(); speech?.shutdown(); speech = null
        listening = false
        restoreAudio()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onPartialResult(h: String?) {}
    override fun onFinalResult(h: String?) {}
    override fun onTimeout() { main.post { endListening() } }
    override fun onError(e: Exception?) { Log.e(TAG, "Vosk error", e); main.post { endListening() } }

    override fun onResult(hypothesis: String?) {
        val text = try { JSONObject(hypothesis ?: return).optString("text") } catch (e: Exception) { return }
        if (text.isBlank()) return
        Log.d(TAG, "Heard: $text")
        prefs.edit().putString("last_heard", text).apply()
        main.post {
            endListening()
            val cmd = CommandParser.parse(text)
            if (cmd == null) say("I heard $text. Say call, unlock, or open") else handle(cmd)
        }
    }

    // ---------- mic routing to the earbuds (best effort) ----------
    private fun routeToBuds() {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (dev != null) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    budsRouted = am.setCommunicationDevice(dev)
                }
            } else if (am.isBluetoothScoAvailableOffCall) {
                am.startBluetoothSco(); budsRouted = true
            }
        } catch (e: SecurityException) { Log.w(TAG, "No Bluetooth permission, using phone mic") }
    }

    private fun restoreAudio() {
        if (!budsRouted) return
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) { am.clearCommunicationDevice(); am.mode = AudioManager.MODE_NORMAL }
        else am.stopBluetoothSco()
        budsRouted = false
    }

    // ---------- commands ----------
    private fun handle(cmd: Command) {
        when (cmd) {
            is Command.CallNumber -> if (canCall()) placeCall(cmd.number, cmd.number.chunked(1).joinToString(" "))
            is Command.CallName -> {
                if (!canCall()) return
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    say("I don't have permission to read contacts"); return
                }
                val match = ContactResolver.find(this, cmd.name)
                if (match == null) say("I heard ${cmd.name}, but no contact matches")
                else placeCall(match.number, match.name)
            }
            Command.Unlock -> doUnlock { say("Unlocked") }
            is Command.OpenApp -> {
                val app = AppResolver.find(this, cmd.name)
                if (app == null) { say("I couldn't find an app called ${cmd.name}"); return }
                doUnlock { UnlockService.instance?.launch(app.second) }
            }
        }
    }

    private fun canCall(): Boolean {
        val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (!ok) say("I don't have permission to place calls")
        return ok
    }

    /** Runs [then] once the phone is unlocked (immediately if it already is). */
    private fun doUnlock(then: () -> Unit) {
        val svc = UnlockService.instance
        if (svc == null) { say("Turn on the Nova unlock service in accessibility settings"); return }
        if (!svc.isLocked()) { then(); return }
        val pin = PinStore.load(this)
        if (pin == null) { say("Save your unlock PIN in the app first"); return }
        say("Unlocking")
        main.postDelayed({
            svc.unlock(pin) { ok -> if (ok) then() else say("I couldn't unlock the phone") }
        }, 1000)
    }

    private fun placeCall(number: String, spokenName: String) {
        say("Calling $spokenName")
        main.postDelayed({
            try {
                getSystemService(TelecomManager::class.java)
                    .placeCall(Uri.fromParts("tel", number, null), Bundle())
            } catch (e: SecurityException) { Log.e(TAG, "placeCall denied", e) }
        }, 1300)
    }

    private fun say(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "nova") }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        endListening()
        session?.release(); session = null
        silence?.release(); silence = null
        model?.close(); model = null
        tts?.shutdown(); tts = null
        super.onDestroy()
    }
}
