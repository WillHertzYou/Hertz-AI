package com.hertz.ai

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import com.hertz.ai.agent.Automations
import com.hertz.ai.agent.MemoryStore
import com.hertz.ai.llm.HertzBrain
import com.hertz.ai.llm.ModelManager
import com.hertz.ai.voice.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HertzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        CrashLog.init(this)
        ModelManager.init(this)
        HertzBrain.init(this)
        MemoryStore.init(this)
        Automations.init(this)
        com.hertz.ai.agent.Uploads.init(this)
        Voice.init(this)
    }
}

/**
 * Why Hertz last closed unexpectedly (crash, freeze, or a library shutting it down), shown in Setup
 * so it can be copied and reported. There's no other way to see this on the phone itself.
 */
object CrashLog {
    val last = MutableStateFlow<String?>(null)
    private lateinit var file: File

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText("Thread ${thread.name}\n" + e.stackTraceToString().take(8000)) }
            previous?.uncaughtException(thread, e)
        }
        runCatching { readLastExit(ctx) }
    }

    private fun readLastExit(ctx: Context) {
        val exit = ctx.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return
        if (exit.timestamp <= Prefs.lastExitSeen) return
        Prefs.lastExitSeen = exit.timestamp
        val what = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "crashed"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed inside a native library"
            ApplicationExitInfo.REASON_ANR -> "froze and Android closed it"
            ApplicationExitInfo.REASON_EXIT_SELF -> if (exit.status != 0) "was shut down by a library (exit code ${exit.status})" else null
            else -> null
        } ?: return
        val details = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> runCatching { file.readText() }.getOrNull()
            // A freeze comes with the stack of every thread; the first lines show what the app was stuck on.
            ApplicationExitInfo.REASON_ANR -> runCatching {
                exit.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(60).joinToString("\n") }
            }.getOrNull()
            else -> null
        }
        val time = SimpleDateFormat("d MMM, h:mm a", Locale.US).format(Date(exit.timestamp))
        last.value = buildString {
            append("Hertz $what on $time.")
            exit.description?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
                ?.let { append(" (Hertz $it)") }
            if (!details.isNullOrBlank()) append("\n\n").append(details)
        }
        file.delete()
    }

    fun dismiss() { last.value = null }
}

/** Small key/value settings store. */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("hertz", Context.MODE_PRIVATE)
    }

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    var voiceName: String?
        get() = sp.getString("voice", null)
        set(v) = sp.edit().putString("voice", v).apply()

    var downloadId: Long
        get() = sp.getLong("dl", -1L)
        set(v) = sp.edit().putLong("dl", v).apply()

    var brainId: String
        get() = sp.getString("brain", "full") ?: "full"
        set(v) = sp.edit().putString("brain", v).apply()

    var greeted: Long
        get() = sp.getLong("greeted", 0L)
        set(v) = sp.edit().putLong("greeted", v).apply()

    /** Free on-device neural voice (downloaded once in Setup). */
    var neuralVoice: Boolean
        get() = sp.getBoolean("neuralVoice", true)
        set(v) = sp.edit().putBoolean("neuralVoice", v).apply()

    /** Kokoro speaker id; 26 = George (British male). */
    var neuralSpeaker: Int
        get() = sp.getInt("neuralSpeaker", 26)
        set(v) = sp.edit().putInt("neuralSpeaker", v).apply()

    /** Light "AI system" effect on the neural voice. */
    var aiVoiceEffect: Boolean
        get() = sp.getBoolean("aiVoiceEffect", true)
        set(v) = sp.edit().putBoolean("aiVoiceEffect", v).apply()

    /** Let the model reason step by step before acting (slower, fewer mistakes). */
    var thinking: Boolean
        get() = sp.getBoolean("thinking", true)
        set(v) = sp.edit().putBoolean("thinking", v).apply()

    /** Optional cloud brain for hard questions: "claude" or "openai" (any OpenAI-compatible service). */
    var cloudProvider: String
        get() = sp.getString("cloudProvider", "claude") ?: "claude"
        set(v) = sp.edit().putString("cloudProvider", v).apply()

    var cloudKey: String
        get() = sp.getString("cloudKey", "") ?: ""
        set(v) = sp.edit().putString("cloudKey", v.trim()).apply()

    var cloudModel: String
        get() = sp.getString("cloudModel", "") ?: ""
        set(v) = sp.edit().putString("cloudModel", v.trim()).apply()

    var cloudBaseUrl: String
        get() = sp.getString("cloudBase", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(v) = sp.edit().putString("cloudBase", v.trim().trimEnd('/')).apply()

    var wakeWord: Boolean
        get() = sp.getBoolean("wakeWord", false)
        set(v) = sp.edit().putBoolean("wakeWord", v).apply()

    /** Path of a brain file known to have no vision part (so Hertz loads it without eyes). */
    var noVisionModel: String
        get() = sp.getString("noVisionModel", "") ?: ""
        set(v) = sp.edit().putString("noVisionModel", v).apply()

    /** The floating orb over other apps (Setup › Floating orb). */
    var showOrb: Boolean
        get() = sp.getBoolean("showOrb", true)
        set(v) = sp.edit().putBoolean("showOrb", v).apply()

    /** Set while the natural voice loads; still set at the next start means it crashed the app. */
    var neuralVoiceLoading: Boolean
        get() = sp.getBoolean("neuralVoiceLoading", false)
        // commit, not apply: it must be on disk before the native library might kill the process.
        set(v) { sp.edit().putBoolean("neuralVoiceLoading", v).commit() }

    /** Time of the last unexpected exit already reported in Setup. */
    var lastExitSeen: Long
        get() = sp.getLong("lastExitSeen", 0L)
        set(v) = sp.edit().putLong("lastExitSeen", v).apply()

    /** Speculative decoding (about twice as fast on the graphics chip) when the brain file supports it. */
    var fastDecoding: Boolean
        get() = sp.getBoolean("fastDecoding", true)
        set(v) = sp.edit().putBoolean("fastDecoding", v).apply()

    var maxSteps: Int
        get() = sp.getInt("maxSteps", 40)
        set(v) = sp.edit().putInt("maxSteps", v).apply()
}
