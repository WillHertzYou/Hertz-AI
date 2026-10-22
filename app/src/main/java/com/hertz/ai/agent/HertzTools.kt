package com.hertz.ai.agent

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.view.KeyEvent
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.hertz.ai.Prefs
import com.hertz.ai.device.HertzAccessibilityService
import com.hertz.ai.device.HertzNotificationListener
import com.hertz.ai.llm.HertzBrain
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything Hertz can do on the phone. The model sees each @Tool (in snake_case, e.g.
 * read_screen, open_app) and calls them on its own while working on a task.
 */
class HertzTools(private val ctx: Context) : ToolSet {

    private val home: File = Environment.getExternalStorageDirectory()

    /** Every tool call goes through here: stop button, step limit, and the activity feed. */
    private fun <T> step(label: String, block: () -> T): T {
        if (HertzBrain.stopRequested) throw IllegalStateException("The user pressed Stop. End the task now.")
        HertzBrain.stepsThisTask++
        if (HertzBrain.stepsThisTask > Prefs.maxSteps) {
            throw IllegalStateException("Step limit reached. Stop and tell the user what is left to do.")
        }
        val id = ChatStore.action(label)
        val result = block()
        // For actions that change something, show what actually happened instead of "Doing X…".
        if (result is String && label.split(' ').first() !in quietVerbs) {
            val line = result.lineSequence().firstOrNull()?.trim().orEmpty()
            if (line.isNotEmpty() && line.length <= 160) ChatStore.updateAction(id, line)
        }
        return result
    }

    /** Read-only steps keep their short label; their results are long data, not a summary. */
    private val quietVerbs = setOf("Reading", "Looking", "Searching", "Listing", "Checking", "Waiting", "Asking")

    private fun screen(): HertzAccessibilityService =
        HertzAccessibilityService.instance
            ?: throw IllegalStateException("Screen control is off. Ask the user to enable Hertz in Settings > Accessibility.")

    private fun resolve(path: String): File {
        val p = path.trim().removePrefix("~/")
        return if (p.startsWith("/")) File(p) else File(home, p)
    }

    private fun settle(ms: Long = 700) = Thread.sleep(ms)

    /** Path as the user would say it, e.g. "Documents/notes.txt". */
    private fun rel(f: File): String = f.path.removePrefix(home.path + "/")

    private fun preview(text: String, max: Int = 80): String {
        val one = text.replace('\n', ' ').trim()
        return if (one.length > max) one.take(max) + "…" else one
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    // ================= Screen =================

    @Tool(description = "Read what is currently on the screen. Returns numbered elements like [5] Button \"Send\" (tap). Call before tapping or typing, and again after each action.")
    fun readScreen(): String = step("Reading the screen") { screen().readScreen() }

    @Tool(description = "Tap an element by its number from the latest read_screen.")
    fun tap(@ToolParam(description = "Element number from read_screen") elementId: Int): String =
        step("Tapping [$elementId]") { screen().tap(elementId).also { settle() } }

    @Tool(description = "Long-press an element by its number from the latest read_screen.")
    fun longPress(@ToolParam(description = "Element number from read_screen") elementId: Int): String =
        step("Long-pressing [$elementId]") { screen().tap(elementId, long = true).also { settle() } }

    @Tool(description = "Tap at exact screen coordinates in pixels. Use only when an element has no number.")
    fun tapAt(
        @ToolParam(description = "X in pixels") x: Int,
        @ToolParam(description = "Y in pixels") y: Int,
    ): String = step("Tapping at $x,$y") {
        (if (screen().tapAt(x.toFloat(), y.toFloat())) "Tapped at $x,$y." else "Tap failed.").also { settle() }
    }

    @Tool(description = "Type text into a text field. Replaces what is in the field.")
    fun typeText(
        @ToolParam(description = "The text to type") text: String,
        @ToolParam(description = "Element number of the text field; 0 to use the focused field") elementId: Int = 0,
        @ToolParam(description = "Press enter/search/send after typing") pressEnter: Boolean = false,
    ): String = step("Typing “${text.take(40)}”") {
        screen().typeText(elementId.takeIf { it > 0 }, text, pressEnter).also { settle(500) }
    }

    @Tool(description = "Scroll the screen. 'down' shows content further down the page.")
    fun scroll(@ToolParam(description = "up, down, left or right") direction: String): String =
        step("Scrolling $direction") { screen().scroll(direction).also { settle(600) } }

    @Tool(description = "Swipe from one point to another, in pixels.")
    fun swipe(
        @ToolParam(description = "Start X") x1: Int,
        @ToolParam(description = "Start Y") y1: Int,
        @ToolParam(description = "End X") x2: Int,
        @ToolParam(description = "End Y") y2: Int,
        @ToolParam(description = "Duration in milliseconds, default 350") durationMs: Int = 350,
    ): String = step("Swiping") {
        val ok = screen().swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs.toLong())
        (if (ok) "Swiped." else "Swipe failed.").also { settle(500) }
    }

    @Tool(description = "Press a system key: back, home, recents, notifications, quick_settings, screenshot, lock, power, split_screen.")
    fun pressKey(@ToolParam(description = "Which key") key: String): String =
        step("Pressing $key") { screen().pressKey(key).also { settle() } }

    @Tool(description = "Wait for something to load.")
    fun waitSeconds(@ToolParam(description = "Seconds to wait, up to 15") seconds: Double): String =
        step("Waiting ${seconds}s") {
            Thread.sleep((seconds.coerceIn(0.1, 15.0) * 1000).toLong()); "Waited ${seconds}s."
        }

    // ================= Apps =================

    private data class AppEntry(val label: String, val pkg: String)

    @Suppress("DEPRECATION")
    private fun launchableApps(): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    @Tool(description = "Open an installed app by name, e.g. 'Spotify', 'Settings', 'Camera'.")
    fun openApp(@ToolParam(description = "App name") name: String): String = step("Opening $name") {
        val q = name.trim().lowercase()
        val apps = launchableApps()
        val app = apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q.replace(" ", "")) }
            ?: return@step "No app called \"$name\". Use list_apps to see what is installed."
        val launch = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return@step "${app.label} can't be opened directly."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        (HertzAccessibilityService.instance ?: ctx).startActivity(launch)
        settle(1500)
        "Opened the ${app.label} app."
    }

    @Tool(description = "List installed apps, optionally filtered by a word.")
    fun listApps(@ToolParam(description = "Optional filter word") filter: String = ""): String = step("Listing apps") {
        launchableApps().filter { filter.isBlank() || it.label.contains(filter, true) }
            .joinToString(", ") { it.label }.ifEmpty { "No matching apps." }.take(3000)
    }

    @Tool(description = "Open a web address, phone link (tel:), map (geo:) or other link in the right app.")
    fun openLink(@ToolParam(description = "A URL such as https://..., tel:..., mailto:..., geo:...") url: String): String =
        step("Opening $url") {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (HertzAccessibilityService.instance ?: ctx).startActivity(i)
            settle(1500)
            "Opened $url."
        }

    // ================= Internet =================

    @Tool(description = "Search the internet and get the top results (title, link, snippet) back directly. Use for news, facts, prices, how-tos, anything current. Then use read_web_page on the best link for details.")
    fun webSearch(@ToolParam(description = "What to search for") query: String): String = step("Searching the web for “$query”") {
        val results = try { Web.search(query) } catch (t: Throwable) {
            return@step "Search failed (${t.message}). Check the phone is online, or use open_link to search in the browser."
        }
        if (results.isEmpty()) return@step "No results for “$query”."
        "Top results for “$query”:\n" + results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }.joinToString("\n")
    }

    @Tool(description = "Read a web page and get its text back (title plus main text). Works with any http/https link.")
    fun readWebPage(@ToolParam(description = "The page address, e.g. https://example.com/article") url: String): String =
        step("Reading ${Uri.parse(if (url.contains("://")) url else "https://$url").host ?: url}") {
            val page = try { Web.fetch(url) } catch (t: Throwable) {
                return@step "Couldn't read $url: ${t.message}"
            }
            "Page: ${page.title}\nAddress: ${page.url}\n\n${page.text}"
        }

    @Tool(description = "Download a file from the internet and save it on the phone (default folder: Download).")
    fun downloadFile(
        @ToolParam(description = "Direct link to the file") url: String,
        @ToolParam(description = "Where to save it, e.g. Download/manual.pdf. Omit to keep the file's own name in Download.") saveAs: String = "",
    ): String = step("Downloading ${Uri.parse(url).lastPathSegment ?: "file"}") {
        val name = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "download"
        val dest = resolve(saveAs.ifBlank { "Download/$name" })
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        try {
            // Follow redirects ourselves: Android won't follow one from http to https.
            var current = url
            var hops = 0
            var conn: java.net.HttpURLConnection
            var code: Int
            do {
                conn = (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                code = conn.responseCode
                val loc = if (code in 300..399) conn.getHeaderField("Location") else null
                if (loc != null) {
                    current = java.net.URL(java.net.URL(current), loc).toString()
                    conn.disconnect()
                }
            } while (loc != null && ++hops <= 5)
            if (code !in 200..299) {
                conn.disconnect()
                return@step "Download failed: the site answered with error $code."
            }
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IllegalStateException("couldn't save to ${rel(dest)}")
        } catch (t: Throwable) {
            part.delete()
            return@step "Download failed: ${t.message}"
        }
        "Downloaded ${dest.name} (${human(dest.length())}) and saved it to ${rel(dest)}."
    }

    // ================= Files =================

    @Tool(description = "List the files and folders inside a folder. Use \"\" for the top of internal storage.")
    fun listFiles(@ToolParam(description = "Folder path, e.g. Download or DCIM/Camera") path: String = ""): String =
        step("Looking in /${path.trim('/')}") {
            val dir = resolve(path)
            if (!dir.exists()) return@step "Folder not found: ${dir.path}"
            if (!dir.isDirectory) return@step "${dir.path} is a file (${human(dir.length())})."
            val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return@step "Can't open ${dir.path}. Is 'All files access' allowed for Hertz?"
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val lines = items.take(200).map {
                if (it.isDirectory) "📁 ${it.name}/" else "${it.name}  (${human(it.length())}, ${fmt.format(Date(it.lastModified()))})"
            }
            "${dir.path} has ${items.size} items:\n" + lines.joinToString("\n") +
                if (items.size > 200) "\n…and ${items.size - 200} more" else ""
        }

    @Tool(description = "Read a text file (notes, code, csv, json, txt, md, html…).")
    fun readFile(@ToolParam(description = "File path") path: String): String = step("Reading ${File(path).name}") {
        val f = resolve(path)
        if (!f.isFile) return@step "File not found: ${f.path}"
        val head = f.inputStream().use { s -> ByteArray(1024).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
        if (head.any { it == 0.toByte() }) return@step "${f.name} is a binary file (${human(f.length())}); use open_file to view it."
        val buf = CharArray(6001)
        val n = f.bufferedReader().use { r ->
            var total = 0
            while (total < buf.size) { val k = r.read(buf, total, buf.size - total); if (k < 0) break; total += k }
            total
        }
        if (n > 6000) String(buf, 0, 6000) + "\n…(truncated; the file is ${human(f.length())})" else String(buf, 0, n)
    }

    @Tool(description = "Create a new file or replace an existing file with the given text. Creates folders as needed.")
    fun writeFile(
        @ToolParam(description = "File path, e.g. Documents/shopping.txt") path: String,
        @ToolParam(description = "Full text content of the file") content: String,
    ): String = step("Writing ${File(path).name}") {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        val existed = f.isFile
        val oldLines = if (existed) f.readLines().size else 0
        f.writeText(content)
        val lines = content.lines().size
        if (existed) "Rewrote ${rel(f)}: it went from $oldLines to $lines lines. It now starts with “${preview(content)}”."
        else "Created ${rel(f)} ($lines lines, ${human(f.length())}). It starts with “${preview(content)}”."
    }

    @Tool(description = "Add text to the end of a file (creates it if missing).")
    fun appendToFile(
        @ToolParam(description = "File path") path: String,
        @ToolParam(description = "Text to add") content: String,
    ): String = step("Adding to ${File(path).name}") {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        val existed = f.exists()
        f.appendText(if (existed && f.length() > 0) "\n$content" else content)
        val added = content.lines().size
        (if (existed) "Added $added line(s) to the end of ${rel(f)}" else "Created ${rel(f)} with $added line(s)") +
            ": “${preview(content)}”."
    }

    @Tool(description = "Replace every occurrence of some text inside a file.")
    fun editFile(
        @ToolParam(description = "File path") path: String,
        @ToolParam(description = "Exact text to find") find: String,
        @ToolParam(description = "Text to put in its place") replaceWith: String,
    ): String = step("Editing ${File(path).name}") {
        val f = resolve(path)
        if (!f.isFile) return@step "File not found: ${f.path}"
        val old = f.readText()
        val count = old.split(find).size - 1
        if (count == 0) return@step "That text isn't in ${f.name}."
        f.writeText(old.replace(find, replaceWith))
        "Edited ${rel(f)}: changed “${preview(find, 60)}” to “${preview(replaceWith, 60)}”" +
            (if (count > 1) " in $count places." else ".")
    }

    @Tool(description = "Create a folder (and any missing parent folders).")
    fun makeFolder(@ToolParam(description = "Folder path") path: String): String = step("Creating folder ${File(path).name}") {
        val d = resolve(path)
        if (d.isDirectory) "The folder ${rel(d)} already exists." else if (d.mkdirs()) "Created the folder ${rel(d)}." else "Couldn't create ${rel(d)}."
    }

    @Tool(description = "Move or rename a file or folder.")
    fun moveFile(
        @ToolParam(description = "Current path") from: String,
        @ToolParam(description = "New path (a folder ending in / keeps the name)") to: String,
    ): String = step("Moving ${File(from).name}") {
        val src = resolve(from)
        if (!src.exists()) return@step "Not found: ${src.path}"
        var dst = resolve(to)
        if (to.endsWith("/") || dst.isDirectory) dst = File(dst, src.name)
        if (src.isDirectory && (dst.canonicalPath + "/").startsWith(src.canonicalPath + "/")) {
            return@step "Can't move ${rel(src)} into itself."
        }
        dst.parentFile?.mkdirs()
        val renamed = src.parentFile?.canonicalPath == dst.parentFile?.canonicalPath
        if (!src.renameTo(dst)) {
            // Different drive: copy, and only remove the original once the copy is complete.
            val copied = runCatching { src.copyRecursively(dst, overwrite = true) }.getOrDefault(false)
            if (!copied) return@step "Couldn't move ${rel(src)}; the original is untouched."
            src.deleteRecursively()
        }
        if (renamed) "Renamed ${rel(src)} to ${dst.name}."
        else "Moved ${src.name} from ${rel(src.parentFile!!)} to ${rel(dst.parentFile!!)}."
    }

    @Tool(description = "Copy a file or folder.")
    fun copyFile(
        @ToolParam(description = "Source path") from: String,
        @ToolParam(description = "Destination path (a folder ending in / keeps the name)") to: String,
    ): String = step("Copying ${File(from).name}") {
        val src = resolve(from)
        if (!src.exists()) return@step "Not found: ${src.path}"
        var dst = resolve(to)
        if (to.endsWith("/") || dst.isDirectory) dst = File(dst, src.name)
        if (src.isDirectory && (dst.canonicalPath + "/").startsWith(src.canonicalPath + "/")) {
            return@step "Can't copy ${rel(src)} into itself."
        }
        dst.parentFile?.mkdirs()
        src.copyRecursively(dst, overwrite = true)
        "Copied ${rel(src)} to ${rel(dst)}."
    }

    @Tool(description = "Delete a file or a folder and everything inside it.")
    fun deleteFile(@ToolParam(description = "Path to delete") path: String): String = step("Deleting ${File(path).name}") {
        val f = resolve(path)
        if (!f.exists()) return@step "Not found: ${f.path}"
        if (f.canonicalPath == home.canonicalPath || (home.canonicalPath + "/").startsWith(f.canonicalPath.trimEnd('/') + "/")) {
            return@step "Refusing to delete all of internal storage."
        }
        val what = if (f.isDirectory) "the folder ${rel(f)} and everything in it (${f.walkTopDown().count { it.isFile }} files)"
            else "${rel(f)} (${human(f.length())})"
        if (f.deleteRecursively()) "Deleted $what." else "Couldn't delete ${rel(f)}."
    }

    @Tool(description = "Find files by name anywhere under a folder (searches subfolders).")
    fun findFiles(
        @ToolParam(description = "Part of the file name, e.g. 'invoice' or '.pdf'") nameContains: String,
        @ToolParam(description = "Folder to search in; \"\" for all of internal storage") inFolder: String = "",
    ): String = step("Searching for “$nameContains”") {
        val root = resolve(inFolder)
        val deadline = System.currentTimeMillis() + 20_000 // don't let a huge search stall the task
        var looked = 0
        val hits = root.walkTopDown()
            .onEnter { !(it.name == "data" && it.parentFile?.name == "Android") && !it.name.startsWith(".") && System.currentTimeMillis() < deadline }
            .maxDepth(10)
            .filter { looked++; it.isFile && it.name.contains(nameContains, ignoreCase = true) }
            .take(60).toList()
        val cut = if (System.currentTimeMillis() >= deadline) " (stopped early after looking at $looked items; try a more specific folder)" else ""
        if (hits.isEmpty()) "No files matching “$nameContains” in ${root.path}$cut."
        else hits.joinToString("\n") { "${it.path.removePrefix(home.path + "/")}  (${human(it.length())})" } + cut
    }

    @Tool(description = "Open a file (photo, video, PDF, document, music…) in the app that normally opens it.")
    fun openFile(@ToolParam(description = "File path") path: String): String = step("Opening ${File(path).name}") {
        val f = resolve(path)
        if (!f.isFile) return@step "File not found: ${f.path}"
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        (HertzAccessibilityService.instance ?: ctx).startActivity(i)
        settle(1200)
        "Opened ${f.name}."
    }

    @Tool(description = "Show every storage drive (internal storage, USB drives, SD cards) with free space.")
    fun listDrives(): String = step("Checking drives") {
        val sm = ctx.getSystemService(StorageManager::class.java)
        sm.storageVolumes.joinToString("\n") { v ->
            val dir = v.directory
            if (dir == null) "${v.getDescription(ctx)}: not mounted"
            else {
                val st = StatFs(dir.path)
                "${v.getDescription(ctx)} at ${dir.path}: ${human(st.availableBytes)} free of ${human(st.totalBytes)}"
            }
        }
    }

    // ================= Phone =================

    @Tool(description = "Get the date, time, battery level and charging state.")
    fun deviceStatus(): String = step("Checking the phone") {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.US).format(Date())
        "It is $now. Battery $pct%${if (bm.isCharging) ", charging" else ""}. " +
            "Free storage: ${human(StatFs(home.path).availableBytes)}."
    }

    @Tool(description = "Copy text to the clipboard so it can be pasted.")
    fun copyToClipboard(@ToolParam(description = "Text to copy") text: String): String = step("Copying to clipboard") {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hertz", text))
        "Copied."
    }

    // ================= Seeing =================

    private val pendingImages = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

    /** Pictures captured by look_at_screen / look_at_file, handed to the model once. */
    fun takePendingImages(): List<ByteArray> = synchronized(pendingImages) {
        pendingImages.toList().also { pendingImages.clear() }
    }

    @Tool(description = "Take a screenshot and look at it. Use when read_screen isn't enough: pictures, games, unlabeled icons, charts, or to check what something looks like.")
    fun lookAtScreen(@ToolParam(description = "What you want to find out from the screenshot") question: String = ""): String =
        step("Looking at the screen") {
            if (!HertzBrain.canSee) return@step "This brain can't see images. Use read_screen instead."
            val img = screen().screenshotJpeg() ?: return@step "Couldn't take a screenshot right now."
            pendingImages += img
            "Screenshot taken. It will be shown to you now." + if (question.isNotBlank()) " Look for: $question" else ""
        }

    @Tool(description = "Look at a photo or image file, or one page of a PDF, on the phone (for example a file the user uploaded). Use it to read documents, describe photos or answer questions about them.")
    fun lookAtFile(
        @ToolParam(description = "File path, e.g. Hertz/Uploads/receipt.jpg or Download/manual.pdf") path: String,
        @ToolParam(description = "For a PDF, the page number to look at (1 = first page)") page: Int = 1,
    ): String = step("Looking at ${File(path).name}") {
        if (!HertzBrain.canSee) return@step "This brain can't see images. Use read_file for text files instead."
        val f = resolve(path)
        if (!f.isFile) return@step "File not found: ${f.path}"
        if (pendingImages.size >= HertzBrain.MAX_IMAGES) return@step "You can look at ${HertzBrain.MAX_IMAGES} pictures at a time; look at these first."
        val isPdf = f.extension.equals("pdf", true)
        val img = Uploads.imageFor(f, page) ?: return@step "${f.name} isn't a picture or PDF I can open. Try read_file or open_file."
        pendingImages += img
        if (isPdf) "Page ${page.coerceIn(1, Uploads.pdfPages(f).coerceAtLeast(1))} of ${Uploads.pdfPages(f)} of ${f.name} will be shown to you now."
        else "${f.name} will be shown to you now."
    }

    // ================= Memory =================

    @Tool(description = "Save a fact about the user to long-term memory (e.g. their name, school, favourite team, how they like things done).")
    fun remember(@ToolParam(description = "The fact, written about the user, e.g. 'Their name is William'") fact: String): String =
        step("Remembering") {
            if (MemoryStore.add(fact)) "Saved to memory: “${preview(fact, 100)}”." else "I already knew that."
        }

    @Tool(description = "Delete facts from long-term memory that contain the given words.")
    fun forget(@ToolParam(description = "Words from the fact to forget") about: String): String = step("Forgetting") {
        val gone = MemoryStore.forget(about)
        if (gone.isEmpty()) "Nothing in memory matched “$about”." else "Forgot: " + gone.joinToString("; ") { "“$it”" } + "."
    }

    // ================= Phone =================

    @Tool(description = "Set an alarm.")
    fun setAlarm(
        @ToolParam(description = "Hour, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int,
        @ToolParam(description = "Optional label") label: String = "",
    ): String = step("Setting an alarm") {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        (HertzAccessibilityService.instance ?: ctx).startActivity(i)
        "Set an alarm for ${clock(hour, minute)}" + (if (label.isNotBlank()) " called “$label”." else ".")
    }

    @Tool(description = "Start a countdown timer.")
    fun setTimer(
        @ToolParam(description = "Length in minutes (can be a decimal, e.g. 0.5 for 30 seconds)") minutes: Double,
        @ToolParam(description = "Optional label") label: String = "",
    ): String = step("Starting a timer") {
        val secs = (minutes * 60).toInt().coerceAtLeast(1)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        (HertzAccessibilityService.instance ?: ctx).startActivity(i)
        "Started a ${if (secs >= 60) "${secs / 60} minute" + (if (secs % 60 > 0) " ${secs % 60} second" else "") else "$secs second"} timer" +
            (if (label.isNotBlank()) " for “$label”." else ".")
    }

    private fun clock(h: Int, m: Int) = "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @Tool(description = "Look up a contact's phone number by name.")
    fun findContact(@ToolParam(description = "Name or part of a name") name: String): String = step("Looking up $name") {
        if (!granted(Manifest.permission.READ_CONTACTS)) return@step "I need the Contacts permission. Ask the user to allow it in Hertz Setup."
        val hits = contactsMatching(name)
        if (hits.isEmpty()) "No contact matches “$name”." else hits.joinToString("\n") { "${it.first}: ${it.second}" }
    }

    private fun contactsMatching(name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"), null,
        )?.use { c ->
            while (c.moveToNext() && out.size < 10) out += c.getString(0) to c.getString(1)
        }
        return out.distinctBy { it.second.filter(Char::isDigit) }
    }

    /** A phone number as given, or the best contact match for a name. */
    private fun numberFor(who: String): Pair<String, String>? {
        if (who.count { it.isDigit() } >= 5) return who to who
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val hits = contactsMatching(who)
        return hits.firstOrNull { it.first.equals(who, true) } ?: hits.firstOrNull()
    }

    @Tool(description = "Phone call someone, by contact name or number.")
    fun callContact(@ToolParam(description = "Contact name or phone number") who: String): String = step("Calling $who") {
        val (name, number) = numberFor(who) ?: return@step "I couldn't find a number for “$who”."
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        (HertzAccessibilityService.instance ?: ctx).startActivity(
            Intent(action, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        if (action == Intent.ACTION_CALL) "Calling $name ($number)." else "Opened the dialer for $name ($number); tap call to start."
    }

    @Tool(description = "Send a text message (SMS) to a contact or number.")
    fun sendText(
        @ToolParam(description = "Contact name or phone number") who: String,
        @ToolParam(description = "The message to send") message: String,
    ): String = step("Texting $who") {
        if (!granted(Manifest.permission.SEND_SMS)) return@step "I need the SMS permission. Ask the user to allow it in Hertz Setup."
        val (name, number) = numberFor(who) ?: return@step "I couldn't find a number for “$who”."
        val sms = ctx.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(message)
        if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
        else sms.sendTextMessage(number, null, message, null, null)
        "Sent a text to $name ($number): “${preview(message, 100)}”."
    }

    @Tool(description = "List calendar events for a day.")
    fun readCalendar(@ToolParam(description = "'today', 'tomorrow', or a date like 2026-10-03") day: String = "today"): String =
        step("Checking the calendar") {
            if (!granted(Manifest.permission.READ_CALENDAR)) return@step "I need the Calendar permission. Ask the user to allow it in Hertz Setup."
            val start = dayStart(day) ?: return@step "I didn't understand the date “$day”."
            val end = start + 24 * 3600 * 1000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, start); ContentUris.appendId(it, end)
            }.build()
            val fmt = SimpleDateFormat("h:mm a", Locale.US)
            val events = mutableListOf<String>()
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val time = if (c.getInt(2) == 1) "All day" else fmt.format(Date(c.getLong(1)))
                    val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                    events += "$time: ${c.getString(0)}$loc"
                }
            }
            if (events.isEmpty()) "Nothing on the calendar for $day." else "Events for $day:\n" + events.joinToString("\n")
        }

    private fun dayStart(day: String): Long? {
        val c = java.util.Calendar.getInstance()
        when (day.trim().lowercase()) {
            "today", "" -> {}
            "tomorrow" -> c.add(java.util.Calendar.DAY_OF_YEAR, 1)
            else -> c.time = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.trim()) }.getOrNull() ?: return null
        }
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Tool(description = "Add an event to the calendar.")
    fun addCalendarEvent(
        @ToolParam(description = "Event title") title: String,
        @ToolParam(description = "Start, formatted 'yyyy-MM-dd HH:mm' (24-hour)") start: String,
        @ToolParam(description = "Length in minutes, default 60") durationMinutes: Int = 60,
        @ToolParam(description = "Optional location") location: String = "",
    ): String = step("Adding “$title” to the calendar") {
        if (!granted(Manifest.permission.WRITE_CALENDAR)) return@step "I need the Calendar permission. Ask the user to allow it in Hertz Setup."
        val begin = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(start.trim())!!.time }.getOrNull()
            ?: return@step "The start time must look like 2026-10-03 15:30."
        var calId = -1L
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
            null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { c -> if (c.moveToFirst()) calId = c.getLong(0) }
        if (calId < 0) return@step "There's no calendar on this phone I can add events to."
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, begin + durationMinutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return@step "The calendar wouldn't accept the event."
        "Added “$title” to your calendar on " + SimpleDateFormat("EEEE d MMMM 'at' h:mm a", Locale.US).format(Date(begin)) +
            (if (location.isNotBlank()) " at $location." else ".")
    }

    @Tool(description = "Turn the flashlight on or off.")
    fun flashlight(@ToolParam(description = "true for on, false for off") on: Boolean): String = step("Flashlight") {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return@step "This phone has no flashlight I can use."
        cm.setTorchMode(id, on)
        if (on) "Turned the flashlight on." else "Turned the flashlight off."
    }

    @Tool(description = "Set the volume.")
    fun setVolume(
        @ToolParam(description = "Volume from 0 to 100") percent: Int,
        @ToolParam(description = "media, ring, alarm or notification; default media") stream: String = "media",
    ): String = step("Setting the volume") {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = when (stream.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        am.setStreamVolume(s, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
        "Set the ${stream.lowercase()} volume to ${percent.coerceIn(0, 100)}%."
    }

    @Tool(description = "Control music or video that is playing: play, pause, next, previous.")
    fun mediaControl(@ToolParam(description = "play, pause, next or previous") action: String): String = step("Media: $action") {
        val code = when (action.lowercase().trim()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        "Sent “${action.lowercase()}” to the media player."
    }

    // ================= Notifications =================

    private var lastNotifications: List<HertzNotificationListener.Item> = emptyList()

    @Tool(description = "Read the latest notifications (messages, emails, alerts). Optionally filter by app or sender.")
    fun readNotifications(@ToolParam(description = "Optional app or sender name to filter by") filter: String = ""): String =
        step("Reading notifications") {
            val nl = HertzNotificationListener.instance
                ?: return@step "Notification access is off. Ask the user to turn it on in Hertz Setup."
            lastNotifications = nl.recent(filter)
            if (lastNotifications.isEmpty()) return@step "No notifications" + (if (filter.isNotBlank()) " from “$filter”." else ".")
            lastNotifications.mapIndexed { i, n ->
                val ago = ((System.currentTimeMillis() - n.postTime) / 60000).let { if (it < 1) "just now" else "$it min ago" }
                "[${i + 1}] ${n.app} · ${n.title}: ${n.text} ($ago)${if (n.replyAction != null) " (can reply)" else ""}"
            }.joinToString("\n")
        }

    @Tool(description = "Reply to a notification by its number from read_notifications (works for WhatsApp, Messages, Instagram, etc.).")
    fun replyToNotification(
        @ToolParam(description = "Notification number from read_notifications") number: Int,
        @ToolParam(description = "The reply text") message: String,
    ): String = step("Replying") {
        val nl = HertzNotificationListener.instance ?: return@step "Notification access is off."
        val n = lastNotifications.getOrNull(number - 1) ?: return@step "No notification [$number]. Call read_notifications first."
        if (nl.reply(n, message)) "Replied to ${n.title} on ${n.app}: “${preview(message, 100)}”."
        else "That notification can't be replied to directly. Open the app instead."
    }

    @Tool(description = "Dismiss a notification by its number from read_notifications.")
    fun dismissNotification(@ToolParam(description = "Notification number") number: Int): String = step("Dismissing") {
        val nl = HertzNotificationListener.instance ?: return@step "Notification access is off."
        val n = lastNotifications.getOrNull(number - 1) ?: return@step "No notification [$number]."
        if (nl.dismiss(n)) "Dismissed the ${n.app} notification from ${n.title}." else "Couldn't dismiss it."
    }

    // ================= Automations =================

    @Tool(description = "Schedule a task for Hertz to do by itself later: at a time (once, daily or on weekdays) or whenever the phone starts charging.")
    fun scheduleTask(
        @ToolParam(description = "What to do, as an instruction, e.g. 'Tell me the weather and my calendar for today'") instruction: String,
        @ToolParam(description = "'time' or 'charging'") trigger: String = "time",
        @ToolParam(description = "Hour 0-23 (for time)") hour: Int = 7,
        @ToolParam(description = "Minute 0-59 (for time)") minute: Int = 0,
        @ToolParam(description = "once, daily or weekdays") repeat: String = "once",
    ): String = step("Scheduling a task") {
        val a = if (trigger.lowercase().startsWith("charg")) Automations.addOnCharging(instruction)
        else Automations.addTimed(instruction, hour.coerceIn(0, 23), minute.coerceIn(0, 59), repeat.lowercase().let {
            if (it in setOf("daily", "weekdays")) it else "once"
        })
        "Scheduled: ${a.describe()}, I will “${preview(instruction, 80)}”."
    }

    @Tool(description = "List scheduled tasks.")
    fun listScheduledTasks(): String = step("Listing scheduled tasks") {
        Automations.list.value.joinToString("\n") { "${it.describe()}: ${it.instruction}" }.ifEmpty { "No scheduled tasks." }
    }

    @Tool(description = "Cancel a scheduled task, found by words from its instruction.")
    fun cancelScheduledTask(@ToolParam(description = "Words from the task's instruction") which: String): String =
        step("Cancelling a scheduled task") {
            val a = Automations.find(which) ?: return@step "No scheduled task matches “$which”."
            Automations.remove(a.id)
            "Cancelled the task “${preview(a.instruction, 80)}” (${a.describe().lowercase()})."
        }

    // ================= Expert =================

    @Tool(description = "Ask a much larger cloud AI for help with hard questions, homework, long writing or code. Only works if the user set it up.")
    fun askExpert(@ToolParam(description = "The full question, with all the context the expert needs") question: String): String =
        step("Asking the expert") {
            if (!Cloud.configured) return@step "No cloud brain is set up; answer as best you can yourself."
            try { Cloud.ask(question).take(6000) } catch (t: Throwable) { "The expert couldn't be reached: ${t.message}" }
        }
}
