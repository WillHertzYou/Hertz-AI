package com.hertz.ai.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Files and photos attached in chat. They're copied onto the phone in full (no size or count limit,
 * only the phone's free space), so Hertz can keep working with them after the chat moves on.
 */
object Uploads {
    /** A file saved on the phone. */
    data class Saved(val name: String, val file: File, val mime: String) {
        val isImage get() = mime.startsWith("image/")
        val isPdf get() = mime == "application/pdf"
    }

    /** One attachment in the chat box, while it copies and once it's ready. */
    data class Pending(
        val id: Long,
        val name: String,
        val size: Long,
        val copied: Long = 0,
        val saved: Saved? = null,
        val error: String? = null,
    )

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(1)
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending = _pending.asStateFlow()

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /**
     * Where uploads go: Hertz/Uploads in internal storage (visible in the Files app) when Hertz has
     * All files access, otherwise Hertz's own folder, which needs no permission.
     */
    fun folder(): File {
        val shared = File(Environment.getExternalStorageDirectory(), "Hertz/Uploads")
        val dir = if (Environment.isExternalStorageManager()) shared else File(app.getExternalFilesDir(null), "Uploads")
        dir.mkdirs()
        return dir
    }

    /** Starts copying the picked files right away, so big ones are ready by the time you hit send. */
    fun add(uris: List<Uri>) {
        for (uri in uris) {
            val (name, size) = describe(uri)
            val item = Pending(ids.getAndIncrement(), name, size)
            _pending.update { it + item }
            scope.launch { copy(item, uri) }
        }
    }

    fun remove(id: Long) {
        val item = _pending.value.firstOrNull { it.id == id } ?: return
        _pending.update { list -> list.filterNot { it.id == id } }
        // Removed before sending: don't leave the copy behind.
        item.saved?.file?.delete()
    }

    val busy: Boolean get() = _pending.value.any { it.saved == null && it.error == null }

    /** Hands over the finished attachments and clears the chat box. */
    fun take(): List<Saved> {
        val ready = _pending.value.mapNotNull { it.saved }
        _pending.update { list -> list.filter { it.saved == null && it.error == null } }
        return ready
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        return (name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file") to size
    }

    private fun copy(item: Pending, uri: Uri) {
        val dir = folder()
        val target = uniqueFile(dir, item.name.replace('/', '_'))
        try {
            if (item.size > 0 && item.size > StatFs(dir.path).availableBytes) {
                throw IllegalStateException("not enough free space on the phone")
            }
            val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("couldn't open it")
            input.use { inp ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var lastShown = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastShown >= 8L shl 20) {
                            lastShown = done
                            set(item.id) { it.copy(copied = done) }
                        }
                        // Removed from the chat box while copying.
                        if (_pending.value.none { it.id == item.id }) { target.delete(); return }
                    }
                }
            }
            val mime = app.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(target.extension.lowercase())
                ?: "application/octet-stream"
            set(item.id) { it.copy(copied = target.length(), saved = Saved(target.name, target, mime)) }
        } catch (t: Throwable) {
            target.delete()
            set(item.id) { it.copy(error = t.message ?: "copy failed") }
        }
    }

    private fun set(id: Long, change: (Pending) -> Pending) =
        _pending.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 2
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    // ---------- Turning files into pictures the brain can look at ----------

    /**
     * A photo (or one page of a PDF) as a JPEG small enough for the brain's eyes,
     * upright and at most [maxSide] pixels on the long side. Null if it isn't a picture.
     */
    fun imageFor(file: File, page: Int = 1, maxSide: Int = 1024): ByteArray? = runCatching {
        val bmp = if (file.extension.equals("pdf", true)) pdfPage(file, page, maxSide) else photo(file, maxSide)
        bmp?.let { b ->
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 85, out)
            b.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    /** Number of pages in a PDF, or 0 if it can't be opened. */
    fun pdfPages(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }.getOrDefault(0)

    private fun photo(file: File, maxSide: Int): Bitmap? {
        // Read only the size first, so a huge photo is decoded at a fraction of its size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (scale >= 1f && rotation == 0f) return decoded
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun pdfPage(file: File, page: Int, maxSide: Int): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage((page - 1).coerceIn(0, pdf.pageCount - 1)).use { p ->
                    val scale = maxSide.toFloat() / maxOf(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
}
