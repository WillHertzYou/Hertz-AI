package com.hertz.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hertz.ai.Prefs
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

/**
 * A free, natural-sounding neural voice that runs entirely on the phone (Kokoro via sherpa-onnx).
 * Several British male speakers; an optional light "AI system" effect is added on top.
 */
object NeuralVoice {
    private const val PACK = "kokoro-int8-multi-lang-v1_0"
    private const val URL_PACK = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$PACK.tar.bz2"
    const val SIZE_MB = 132

    /** British male speakers in this voice pack (speaker id to name). */
    val speakers = listOf(26 to "George", 27 to "Lewis", 24 to "Daniel", 25 to "Fable")

    sealed interface State {
        data object Missing : State
        data class Downloading(val mb: Int) : State
        data object Loading : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor()
    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null
    @Volatile private var currentId = 0L
    private val _state = MutableStateFlow<State>(State.Missing)
    val state = _state.asStateFlow()

    private val dir get() = File(app.filesDir, "voices/$PACK")

    /** Every file the voice library opens; if one is missing it shuts the whole app down. */
    private val required = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-gb-en.txt", "espeak-ng-data/phontab")

    /** Written only after a complete download, so a pack cut off part-way never counts as installed. */
    private val marker get() = File(dir, ".complete")

    private fun installed() = marker.exists() && required.all { File(dir, it).isFile }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        when {
            // The voice library crashed Hertz while loading last time: switch it off instead of crashing again.
            Prefs.neuralVoiceLoading -> {
                Prefs.neuralVoiceLoading = false
                dir.deleteRecursively()
                _state.value = State.Failed("The natural voice stopped Hertz from working, so it was removed. Tap Get to download it again.")
            }
            installed() -> _state.value = State.Ready
            // Left over from an older version that could save an incomplete voice; that crashes when it speaks.
            dir.exists() -> {
                dir.deleteRecursively()
                _state.value = State.Failed("The natural voice download was incomplete. Tap Get to download it again.")
            }
        }
    }

    val ready: Boolean get() = _state.value == State.Ready

    /** Downloads and unpacks the voice (about 132 MB, once). */
    fun download() {
        if (_state.value is State.Downloading) return
        worker.execute {
            try {
                _state.value = State.Downloading(0)
                // Unpack into a temporary folder, so a cut-off download never looks installed
                // (loading a half-written model crashes the app).
                val root = File(dir.parentFile!!, ".partial").apply { deleteRecursively(); mkdirs() }
                val conn = URL(URL_PACK).openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
                var read = 0L
                val counting = object : java.io.FilterInputStream(conn.getInputStream().buffered(1 shl 16)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) { read += n; _state.value = State.Downloading((read shr 20).toInt()) }
                        return n
                    }
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        val out = File(root, e.name)
                        if (!out.canonicalPath.startsWith(root.canonicalPath)) continue
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                    }
                }
                val unpacked = File(root, PACK)
                if (!required.all { File(unpacked, it).isFile }) {
                    root.deleteRecursively()
                    _state.value = State.Failed("The voice pack looked incomplete. Try again.")
                    return@execute
                }
                synchronized(this) { tts?.release(); tts = null }
                dir.deleteRecursively()
                if (unpacked.renameTo(dir)) marker.createNewFile()
                root.deleteRecursively()
                _state.value = if (installed()) State.Ready else State.Failed("The voice pack looked incomplete. Try again.")
            } catch (t: Throwable) {
                File(dir.parentFile!!, ".partial").deleteRecursively()
                _state.value = State.Failed("Download failed: ${t.message}")
            }
        }
    }

    private fun engine(): OfflineTts {
        tts?.let { return it }
        val d = dir.path
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$d/model.int8.onnx",
                    voices = "$d/voices.bin",
                    tokens = "$d/tokens.txt",
                    dataDir = "$d/espeak-ng-data",
                    lexicon = "$d/lexicon-gb-en.txt",
                    dictDir = File(dir, "dict").takeIf { it.isDirectory }?.path ?: "",
                ),
                numThreads = 4,
            ),
        )
        // If the library kills the app while loading, the next start sees this flag and turns the voice off.
        Prefs.neuralVoiceLoading = true
        val created = try {
            OfflineTts(config = config)
        } catch (t: Throwable) {
            // Damaged files: remove them so Setup offers a fresh download, and use the phone voice meanwhile.
            dir.deleteRecursively()
            _state.value = State.Failed("The natural voice files are damaged. Tap Get to download them again.")
            throw t
        } finally {
            Prefs.neuralVoiceLoading = false
        }
        return created.also { tts = it }
    }

    /** Speaks [text] with the chosen speaker; returns false if the neural voice isn't available. */
    fun speak(text: String, id: Long, onFail: () -> Unit) {
        if (!ready) { onFail(); return }
        currentId = id
        worker.execute {
            if (id != currentId) return@execute
            try {
                val engine = engine()
                val rate = engine.sampleRate()
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                synchronized(this) { track?.release(); track = t }
                t.play()
                val fx = if (Prefs.aiVoiceEffect) SystemVoiceEffect(rate) else null
                // Audio is played as it is generated, so Hertz starts talking almost straight away.
                // Must be a real class, not a plain lambda: the native library looks up
                // invoke(float[]): Integer by name, and Kotlin 2 lambdas don't have that method.
                // The failed lookup aborted the whole app the first time the voice spoke.
                val onChunk = object : (FloatArray) -> Int {
                    override fun invoke(chunk: FloatArray): Int {
                        if (id != currentId) return 0
                        val out = fx?.process(chunk) ?: chunk
                        t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                        return 1
                    }
                }
                engine.generateWithCallback(text, sid = Prefs.neuralSpeaker, speed = 1.05f, callback = onChunk)
                if (id == currentId) {
                    fx?.tail()?.let { t.write(it, 0, it.size, AudioTrack.WRITE_BLOCKING) }
                }
            } catch (t: Throwable) {
                android.util.Log.w("Hertz", "Neural voice failed; using the phone voice", t)
                onFail()
            }
        }
    }

    fun stop() {
        currentId++
        synchronized(this) { runCatching { track?.pause(); track?.flush(); track?.stop() } }
    }
}

/**
 * A light "AI system" colour: a very short doubled echo (slightly metallic sheen)
 * plus a small room reverb. Subtle on purpose, so the voice stays clear.
 */
private class SystemVoiceEffect(rate: Int) {
    private val d1 = FloatArray((rate * 0.011).toInt())  // 11 ms doubling
    private val d2 = FloatArray((rate * 0.017).toInt())  // 17 ms doubling
    private val room = FloatArray((rate * 0.061).toInt()) // 61 ms room echo
    private var i1 = 0; private var i2 = 0; private var ir = 0

    fun process(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (n in input.indices) {
            val x = input[n]
            val a = d1[i1]; val b = d2[i2]; val r = room[ir]
            val y = x * 0.82f + a * 0.22f + b * 0.16f + r * 0.18f
            d1[i1] = x; d2[i2] = x; room[ir] = x + r * 0.35f
            i1 = (i1 + 1) % d1.size; i2 = (i2 + 1) % d2.size; ir = (ir + 1) % room.size
            out[n] = y.coerceIn(-1f, 1f)
        }
        return out
    }

    /** Lets the echo ring out naturally after the last word. */
    fun tail(): FloatArray = process(FloatArray(room.size * 3))
}
