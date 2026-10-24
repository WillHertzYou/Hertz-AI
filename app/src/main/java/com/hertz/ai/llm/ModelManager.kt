package com.hertz.ai.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.hertz.ai.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Gets a Gemma 4 brain onto the phone, either by downloading it straight from
 * Hugging Face or by importing a .litertlm file you already have.
 */
object ModelManager {

    /**
     * A brain Hertz can run. The plain .litertlm files include the vision part (so Hertz can look
     * at screenshots); the "-gpu" files are the same model without it, so they are smaller.
     */
    data class Brain(val id: String, val title: String, val detail: String, val fileName: String, val gb: Double) {
        val url get() = "https://huggingface.co/litert-community/${repo()}/resolve/main/$fileName"
        private fun repo() = fileName.removeSuffix(".litertlm").removeSuffix("-gpu") + "-litert-lm"
        val textOnly get() = fileName.endsWith("-gpu.litertlm")
    }

    val FULL = Brain(
        "full", "Full brain", "Gemma 4 E4B · smartest, best at using apps, can look at the screen",
        "gemma-4-E4B-it.litertlm", 3.66,
    )
    val QUICK = Brain(
        "quick", "Quick brain", "Gemma 4 E2B · downloads and replies faster, a bit less clever, can look at the screen",
        "gemma-4-E2B-it.litertlm", 2.59,
    )
    /** Text-only builds that earlier versions downloaded; still usable if they are already on the phone. */
    private val FULL_LITE = Brain("full-lite", "Full brain (text only)", "Gemma 4 E4B · cannot look at screenshots", "gemma-4-E4B-it-gpu.litertlm", 2.97)
    private val QUICK_LITE = Brain("quick-lite", "Quick brain (text only)", "Gemma 4 E2B · cannot look at screenshots", "gemma-4-E2B-it-gpu.litertlm", 2.01)
    private val all = listOf(FULL, QUICK, FULL_LITE, QUICK_LITE)

    /** What Setup offers: the two full brains, plus any text-only one already on the phone. */
    val choices: List<Brain> get() = listOf(FULL, QUICK) + listOf(FULL_LITE, QUICK_LITE).filter { installed(it) }

    private const val MIN_VALID_BYTES = 500L * 1024 * 1024

    sealed interface State {
        data object Missing : State
        /** [note] says why it isn't moving, e.g. waiting for Wi-Fi, or that it's retrying. */
        data class Downloading(val brain: String, val done: Long, val total: Long, val note: String? = null) : State
        data class Importing(val done: Long, val total: Long) : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null
    private val _state = MutableStateFlow<State>(State.Missing)
    val state = _state.asStateFlow()

    private fun fileOf(b: Brain) = File(app.getExternalFilesDir(null), b.fileName)
    /** A brain that is still downloading is only partly written, so it doesn't count yet. */
    private fun installed(b: Brain) =
        !(Prefs.downloadId >= 0 && b.id == Prefs.brainId) && fileOf(b).let { it.exists() && it.length() > MIN_VALID_BYTES }

    /** The brain that will be loaded: the one the user picked if present, otherwise any installed one. */
    val brain: Brain
        get() = all.firstOrNull { it.id == Prefs.brainId && installed(it) }
            ?: all.firstOrNull { installed(it) }
            ?: all.firstOrNull { it.id == Prefs.brainId } ?: FULL

    val modelFile: File get() = fileOf(brain)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (Prefs.downloadId >= 0) watchDownload(Prefs.downloadId) else refresh()
    }

    fun refresh() {
        if (_state.value is State.Downloading || _state.value is State.Importing) return
        if (Prefs.downloadId >= 0) return
        val anyInstalled = all.any { installed(it) }
        // Keep a failed download's explanation on screen (Setup refreshes every time it reopens).
        if (_state.value is State.Failed && !anyInstalled) return
        _state.value = if (anyInstalled) State.Ready else State.Missing
    }

    /** Automatic restarts left for the current download after a failure that a retry can fix. */
    private var retriesLeft = 0

    fun download(b: Brain) {
        retriesLeft = 3
        startDownload(b)
    }

    private fun startDownload(b: Brain) {
        val dm = app.getSystemService(DownloadManager::class.java)
        if (Prefs.downloadId >= 0) dm.remove(Prefs.downloadId)
        fileOf(b).delete()
        // Check the space first: otherwise Android only says "Download unsuccessful" after a long wait.
        val dir = app.getExternalFilesDir(null)
        val free = dir?.let { runCatching { android.os.StatFs(it.path).availableBytes }.getOrNull() }
        val needed = (b.gb * (1L shl 30)).toLong() + (300L shl 20)
        if (free != null && free < needed) {
            Prefs.downloadId = -1
            _state.value = State.Failed(
                "Not enough space: the ${b.title.lowercase()} needs %.1f GB free and the phone has %.1f GB. ".format(needed / 1073741824.0, free / 1073741824.0) +
                    "Free up some space and tap Get again. If you saved a copy of the brain in Download, use \"I already have a .litertlm file\" instead."
            )
            return
        }
        Prefs.brainId = b.id
        val req = DownloadManager.Request(Uri.parse(b.url))
            .setTitle("Hertz ${b.title.lowercase()} (${b.gb} GB)")
            .setDescription("Downloading Hertz's on-device brain")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(app, null, b.fileName)
        val id = dm.enqueue(req)
        Prefs.downloadId = id
        watchDownload(id)
    }

    /** Switch to a brain that is already downloaded. */
    fun use(b: Brain) {
        Prefs.brainId = b.id
        scope.launch { HertzBrain.unload() }
    }

    fun isInstalled(b: Brain) = installed(b)

    fun cancelDownload() {
        val id = Prefs.downloadId
        if (id >= 0) app.getSystemService(DownloadManager::class.java).remove(id)
        Prefs.downloadId = -1
        watcher?.cancel()
        _state.value = State.Missing
        refresh()
    }

    private fun watchDownload(id: Long) {
        watcher?.cancel()
        watcher = scope.launch {
            val dm = app.getSystemService(DownloadManager::class.java)
            val target = all.firstOrNull { it.id == Prefs.brainId }
            val name = target?.title ?: "Brain"
            while (isActive) {
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (c == null || !c.moveToFirst()) {
                    c?.close(); Prefs.downloadId = -1; _state.value = State.Missing; refresh(); break
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                c.close()
                val brain = all.firstOrNull { it.id == Prefs.brainId }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        Prefs.downloadId = -1
                        if (target != null && !installed(target)) {
                            // Finished but far too small: an error page or a cut-off file, not a brain.
                            fileOf(target).delete()
                            _state.value = State.Failed("The download finished but the file is incomplete. Try again on Wi-Fi.")
                        } else {
                            _state.value = State.Missing; refresh()
                        }
                        break
                    }
                    DownloadManager.STATUS_FAILED -> {
                        Prefs.downloadId = -1
                        if (brain != null && retryable(reason) && retriesLeft > 0) {
                            // Dropped connections and server hiccups usually work on a fresh try.
                            retriesLeft--
                            _state.value = State.Downloading(name, 0, total, "The download stopped (${failureText(reason)}). Trying again…")
                            delay(5000)
                            startDownload(brain)
                        } else {
                            _state.value = State.Failed("The download didn't finish: ${failureText(reason)} (code $reason).")
                        }
                        break
                    }
                    DownloadManager.STATUS_PAUSED -> _state.value = State.Downloading(name, done, total, pausedText(reason))
                    else -> _state.value = State.Downloading(name, done, total)
                }
                delay(1000)
            }
        }
    }

    private fun retryable(reason: Int) = reason in 500..599 || reason in listOf(
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME,
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE, DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNKNOWN,
        408, 429,
    )

    /** Android's download error codes in plain words, with what to do about them. */
    private fun failureText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "the phone ran out of space. Free up about 4 GB and tap Get again"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME ->
            "the connection kept dropping. Use steady Wi-Fi (turn off any VPN) and keep the phone plugged in"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "the phone's storage wasn't available"
        DownloadManager.ERROR_FILE_ERROR -> "the file couldn't be saved on the phone. Restart the phone and try again"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            "the download server sent something unexpected. Try again in a few minutes"
        401, 403 -> "the download server refused the request. Try again in a few minutes"
        404 -> "the brain file wasn't found on the server"
        in 500..599 -> "the download server had a problem. Try again in a few minutes"
        else -> "Android stopped the download"
    }

    private fun pausedText(reason: Int): String? = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Paused: waiting for an internet connection."
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Paused: waiting for Wi-Fi."
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Paused: the connection dropped, Android will retry shortly."
        else -> null
    }

    /** Copy a model file the user picked (e.g. downloaded in Chrome) into Hertz's folder. */
    fun import(uri: Uri) {
        scope.launch {
            var tmp: File? = null
            try {
                val total = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                // Picker URIs usually don't contain the file name, so ask for it.
                val pickedName = runCatching {
                    app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
                val target = all.firstOrNull { pickedName.endsWith(it.fileName) } ?: FULL
                val dest = fileOf(target)
                tmp = File(dest.path + ".part")
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > 32L shl 20) {
                                last = done; _state.value = State.Importing(done, total)
                            }
                        }
                    }
                }
                if (tmp.length() < MIN_VALID_BYTES) throw IllegalStateException("that file is too small to be a Hertz brain")
                // Free the old copy first (the model may be loaded from it).
                if (dest.exists()) HertzBrain.unload()
                dest.delete()
                if (!tmp.renameTo(dest)) throw IllegalStateException("couldn't save the brain file")
                Prefs.brainId = target.id
                if (Prefs.noVisionModel == dest.absolutePath) Prefs.noVisionModel = ""
                _state.value = State.Missing
                refresh()
            } catch (t: Throwable) {
                tmp?.delete()
                _state.value = State.Failed("Import failed: ${t.message}")
            }
        }
    }
}
