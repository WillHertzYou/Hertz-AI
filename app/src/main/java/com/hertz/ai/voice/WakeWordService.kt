package com.hertz.ai.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.hertz.ai.MainActivity
import com.hertz.ai.Prefs
import com.hertz.ai.R
import com.hertz.ai.agent.Agent
import com.hertz.ai.agent.ChatStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Listens for "Hey Hertz" completely offline (Vosk), then hands over to normal speech
 * recognition for the actual request. Runs as a foreground service with a notification,
 * because Android only lets apps use the microphone in the background that way.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private const val CHANNEL = "hertz_wake"

        private val _status = MutableStateFlow("Off")
        val status = _status.asStateFlow()

        fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk/$MODEL_NAME")

        /** Returns false (and doesn't start) if the microphone permission hasn't been granted. */
        fun start(ctx: Context): Boolean {
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _status.value = "Allow the microphone in Setup first"
                return false
            }
            Prefs.wakeWord = true
            return try {
                ctx.startForegroundService(Intent(ctx, WakeWordService::class.java))
                true
            } catch (t: Throwable) {
                _status.value = "Couldn't start: ${t.message}"
                false
            }
        }

        fun stop(ctx: Context) {
            Prefs.wakeWord = false
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
            _status.value = "Off"
        }

        @Volatile private var instance: WakeWordService? = null

        /** Frees the microphone for normal speech recognition. Call on the main thread. */
        fun pause() { instance?.pauseListening() }

        /** Starts listening for "Hey Hertz" again, once the other recogniser has let go of the mic. */
        fun resume() { instance?.let { s -> s.main.removeCallbacks(s.resumeNow); s.main.postDelayed(s.resumeNow, 800) } }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    @Volatile private var handling = false
    @Volatile private var paused = false
    @Volatile private var destroyed = false
    @Volatile private var preparing = false
    private val resumeNow = Runnable { resumeListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Hertz listening", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Hertz is listening for “Hey Hertz”")
            .setContentText("Tap to open Hertz. Turn this off in Setup.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            // Android refuses microphone services started from the background or without the permission.
            _status.value = "Open Hertz to restart “Hey Hertz”"
            stopSelf()
            return
        }
        instance = this
        startPreparing()
    }

    private fun startPreparing() {
        if (preparing) return
        preparing = true
        Thread { try { prepareAndListen() } finally { preparing = false } }.start()
    }

    /** Don't let Android restart us from the background: the app restarts the service when opened. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started again while still alive after a failure (e.g. the model couldn't download offline): try again.
        if (instance === this && model == null) startPreparing()
        return START_NOT_STICKY
    }

    private fun prepareAndListen() {
        try {
            val dir = modelDir(this)
            // Older versions could leave a half-unzipped model behind; only a finished download has the marker.
            if (!File(dir, ".complete").exists()) {
                _status.value = "Downloading the wake-word model (40 MB)…"
                download(dir.parentFile!!)
            }
            if (destroyed) return
            _status.value = "Loading…"
            val m = Model(dir.path)
            // Switched off while the model was downloading or loading: don't grab the microphone.
            if (destroyed) { m.close(); return }
            model = m
            main.post { runCatching { startListening() }.onFailure { _status.value = "Wake word failed: ${it.message}" } }
        } catch (t: Throwable) {
            if (!destroyed) _status.value = "Wake word failed: ${t.message}"
        }
    }

    /** Unzips into a temporary folder first, so a cut-off download never looks like a finished model. */
    private fun download(into: File) {
        into.mkdirs()
        val partial = File(into, ".partial").apply { deleteRecursively(); mkdirs() }
        val conn = URL(MODEL_URL).openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
        ZipInputStream(conn.getInputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(partial, entry.name)
                if (!out.canonicalPath.startsWith(partial.canonicalPath)) continue
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val done = File(partial, MODEL_NAME)
        if (!File(done, "am").exists()) throw IllegalStateException("the download was incomplete")
        val dest = File(into, MODEL_NAME).apply { deleteRecursively() }
        if (!done.renameTo(dest)) throw IllegalStateException("couldn't save the model")
        File(dest, ".complete").createNewFile()
        partial.deleteRecursively()
    }

    private fun startListening() {
        val m = model ?: return
        if (destroyed || paused) return
        // A tiny grammar: the recogniser only has to decide between our phrase and "anything else".
        val rec = Recognizer(m, 16000f, "[\"hey hertz\", \"okay hertz\", \"hertz\", \"[unk]\"]")
        runCatching { speech?.stop(); speech?.shutdown() }
        runCatching { recognizer?.close() }
        recognizer = rec
        speech = SpeechService(rec, 16000f).also { it.startListening(this) }
        _status.value = "Listening for “Hey Hertz”"
        handling = false
    }

    private fun pauseListening() {
        main.removeCallbacks(resumeNow)
        paused = true
        handling = true
        runCatching { speech?.stop() }
    }

    private fun resumeListening() {
        if (destroyed || !paused) return
        paused = false
        val s = speech
        if (s == null) { runCatching { startListening() }; return }
        runCatching { s.startListening(this) }
        handling = false
        _status.value = "Listening for “Hey Hertz”"
    }

    private fun heard(json: String) {
        if (handling) return
        val text = runCatching { JSONObject(json).optString("text", JSONObject(json).optString("partial")) }.getOrDefault("")
        // Only the full phrase counts, so a stray word that sounds like "hertz" doesn't wake it.
        if (!text.contains("hey hertz") && !text.contains("okay hertz")) return
        handling = true
        _status.value = "Heard you"
        main.post {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            ChatStore.status.value = "Yes, sir?"
            // Voice.listen frees the microphone from the wake word and gives it back when done.
            Voice.listen(
                onPartial = { ChatStore.status.value = "“$it”" },
                onResult = { request ->
                    if (request.isNotBlank()) Agent.submit(request)
                    else ChatStore.status.value = Voice.listenError.value ?: "I didn't catch that, sir."
                },
            )
        }
    }

    override fun onPartialResult(hypothesis: String?) { hypothesis?.let { heard(it) } }
    override fun onResult(hypothesis: String?) { hypothesis?.let { heard(it) } }
    override fun onFinalResult(hypothesis: String?) { hypothesis?.let { heard(it) } }
    override fun onError(exception: Exception?) {
        _status.value = "Microphone error: ${exception?.message}. Retrying…"
        main.postDelayed({ if (!paused) runCatching { startListening() } }, 3000)
    }
    override fun onTimeout() { runCatching { startListening() } }

    override fun onDestroy() {
        destroyed = true
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        runCatching { speech?.stop(); speech?.shutdown() }
        speech = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        _status.value = "Off"
        super.onDestroy()
    }
}
