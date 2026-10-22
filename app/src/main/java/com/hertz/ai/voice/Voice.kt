package com.hertz.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as TtsVoice
import com.hertz.ai.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Hertz's voice: a composed British male text-to-speech voice (Jarvis-style), and
 * on-device speech recognition for talking to it.
 */
object Voice {
    private lateinit var app: Context
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _voices = MutableStateFlow<List<String>>(emptyList())
    /** British English voices installed on the phone, best butler candidates first. */
    val voices = _voices.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening = _listening.asStateFlow()

    // Google TTS voice ids that are British male voices, in order of preference.
    private val preferredMale = listOf("en-gb-x-rjs", "en-gb-x-gbd", "en-gb-x-gbb")

    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    @Volatile private var speechId = 0L

    fun init(ctx: Context) {
        app = ctx.applicationContext
        NeuralVoice.init(app)
        // Prefer Google's engine (best British voices); falls back to the default engine if missing.
        tts = TextToSpeech(app, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                applyVoice()
                pending?.let { p -> pending = null; speakPhone(p) }
            }
        }, "com.google.android.tts")
    }

    private fun applyVoice() {
        val engine = tts ?: return
        val british = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "en" && it.locale.country == "GB" }
            .sortedWith(compareBy<TtsVoice>(
                { v -> preferredMale.indexOfFirst { v.name.startsWith(it) }.let { if (it < 0) 99 else it } },
                // Google's online versions of these voices sound far more natural; they fall back offline.
                { !it.isNetworkConnectionRequired },
                { it.name },
            ))
        _voices.value = british.map { it.name }
        val chosen = british.firstOrNull { it.name == Prefs.voiceName } ?: british.firstOrNull()
        if (chosen != null) engine.voice = chosen else engine.language = Locale.UK
        engine.setPitch(0.94f)
        engine.setSpeechRate(1.06f)
    }

    fun setVoice(name: String) {
        Prefs.voiceName = name
        applyVoice()
    }

    fun speak(text: String) {
        val clean = text.replace(Regex("[*_#`>]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        val id = ++speechId
        if (Prefs.neuralVoice && NeuralVoice.ready) {
            // Natural on-device voice; falls back to the phone voice if anything goes wrong.
            NeuralVoice.speak(clean, id) { if (id == speechId) main.post { speakPhone(clean) } }
        } else {
            speakPhone(clean)
        }
    }

    /** Plays a line in the phone voice even when the natural voice is on (for trying phone voices in Setup). */
    fun previewPhoneVoice(text: String) {
        stopSpeaking()
        speakPhone(text)
    }

    private fun speakPhone(text: String) {
        if (!ready) { pending = text; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hertz-${System.nanoTime()}")
    }

    /** A spoken welcome when Hertz is opened, at most once every 30 minutes. */
    fun greet(): String? {
        val now = System.currentTimeMillis()
        if (now - Prefs.greeted < 30 * 60 * 1000) return null
        Prefs.greeted = now
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val line = when (hour) {
            in 5..11 -> listOf(
                "Good morning, sir. All systems are online and at your service.",
                "Morning, sir. I trust you slept well. What's on the agenda today?",
                "Good morning, sir. Hertz is up and running. Where shall we begin?",
            )
            in 12..17 -> listOf(
                "Good afternoon, sir. Hertz is online. How may I be of assistance?",
                "Welcome back, sir. What are we working on?",
                "Good afternoon, sir. All systems are running smoothly.",
            )
            in 18..22 -> listOf(
                "Good evening, sir. All systems are online. What can I do for you tonight?",
                "Evening, sir. Shall we get to work?",
                "Welcome back, sir. At your service, as always.",
            )
            else -> listOf(
                "Burning the midnight oil again, sir? I'm at your service.",
                "Working late, sir. Hertz is online and ready when you are.",
            )
        }.random()
        if (Prefs.speakReplies) speak(line)
        return line
    }

    fun stopSpeaking() {
        speechId++
        pending = null
        tts?.stop()
        NeuralVoice.stop()
    }

    /** Why the last listen failed, in plain words, or null. */
    val listenError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * Listen once and hand back what was said. Must be allowed the microphone permission.
     * "Hey Hertz" is paused meanwhile, because only one recogniser can use the microphone.
     */
    fun listen(onPartial: (String) -> Unit = {}, onDevice: Boolean = true, onResult: (String) -> Unit): Boolean = main.post {
        stopSpeaking()
        recognizer?.destroy()
        WakeWordService.pause()
        listenError.value = null
        val useOnDevice = onDevice && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        val rec = if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        else SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { _listening.value = false }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                _listening.value = false
                WakeWordService.resume()
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                _listening.value = false
                // The offline recogniser may not have this language yet: fall back to the normal one once.
                if (useOnDevice && error in listOf(
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER,
                    )
                ) { listen(onPartial, onDevice = false, onResult = onResult); return }
                listenError.value = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Hertz needs the microphone permission (Setup)."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy; try again in a moment."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet on this phone (or install Speech Services by Google for offline)."
                    else -> "Speech recognition error $error."
                }
                WakeWordService.resume()
                onResult("")
            }
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }
}
