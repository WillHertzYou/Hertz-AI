package com.hertz.ai.agent

import com.hertz.ai.Prefs
import com.hertz.ai.llm.HertzBrain
import com.hertz.ai.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

enum class Role { User, Hertz, Action }

data class ChatMsg(val id: Long, val role: Role, val text: String)

/** The shared conversation feed shown in the app and in the overlay. */
object ChatStore {
    private val ids = AtomicLong(1)
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages = _messages.asStateFlow()

    /** Short live status line for the overlay ("Tapped Wi-Fi", "Thinking…"). */
    val status = MutableStateFlow("")

    fun add(role: Role, text: String): Long {
        val id = ids.getAndIncrement()
        _messages.update { it + ChatMsg(id, role, text) }
        return id
    }

    fun update(id: Long, text: String) {
        _messages.update { list -> list.map { if (it.id == id) it.copy(text = text) else it } }
    }

    /** Adds a line to the activity feed; returns its id so it can be updated with the outcome. */
    fun action(text: String): Long {
        val id = add(Role.Action, text)
        status.value = text
        actionsThisTask += text
        return id
    }

    fun updateAction(id: Long, text: String) {
        update(id, text)
        status.value = text
        if (actionsThisTask.isNotEmpty()) actionsThisTask[actionsThisTask.lastIndex] = text
    }

    /** What happened during the current task, used if the model forgets to summarise. */
    val actionsThisTask = mutableListOf<String>()

    fun clear() {
        _messages.value = emptyList()
    }
}

/** Runs tasks: the one entry point used by chat, voice, overlay, suggestions and macros. */
object Agent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var current: Job? = null
    private val taskLock = Mutex()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Called with the final reply of each task (the overlay listens to this). */
    var onReply: ((String) -> Unit)? = null
    /** Called when a task starts acting on the screen, so the overlay can get out of the way. */
    var onTaskStart: (() -> Unit)? = null

    fun submit(prompt: String, speak: Boolean = Prefs.speakReplies, files: List<Uploads.Saved> = emptyList()) {
        if (prompt.isBlank() && files.isEmpty()) return
        current = scope.launch { run(prompt, speak, files = files) }
    }

    suspend fun run(
        prompt: String,
        speak: Boolean = Prefs.speakReplies,
        showPrompt: String = prompt,
        files: List<Uploads.Saved> = emptyList(),
    ): String = taskLock.withLock { runLocked(prompt, speak, showPrompt, files) }

    private suspend fun runLocked(prompt: String, speak: Boolean, showPrompt: String, files: List<Uploads.Saved>): String {
        val shown = if (files.isEmpty()) showPrompt
        else (showPrompt.ifBlank { "" } + "\n" + files.joinToString("\n") { "📎 ${it.name}" }).trim()
        ChatStore.add(Role.User, shown)
        val replyId = ChatStore.add(Role.Hertz, "")
        _busy.value = true
        ChatStore.status.value = "Thinking…"
        onTaskStart?.invoke()
        Voice.stopSpeaking()
        ChatStore.actionsThisTask.clear()
        return try {
            // Load first: whether the brain can see photos is only known once it's loaded.
            if (files.isNotEmpty()) HertzBrain.ensureLoaded()
            val (fullPrompt, images) = withFiles(prompt, files)
            val reply = HertzBrain.ask(fullPrompt, images) { partial -> ChatStore.update(replyId, partial) }
                .ifBlank { fallbackSummary() }
            ChatStore.update(replyId, reply)
            ChatStore.status.value = reply
            if (speak) Voice.speak(reply)
            onReply?.invoke(reply)
            reply
        } catch (t: Throwable) {
            val msg = "I hit a snag: ${t.message ?: t.javaClass.simpleName}"
            ChatStore.update(replyId, msg)
            ChatStore.status.value = msg
            onReply?.invoke(msg)
            msg
        } finally {
            _busy.value = false
        }
    }

    /**
     * Tells the brain where the attached files are saved, and shows it the photos directly
     * (up to [HertzBrain.MAX_IMAGES]; it can open the rest with look_at_file).
     */
    private fun withFiles(prompt: String, files: List<Uploads.Saved>): Pair<String, List<ByteArray>> {
        if (files.isEmpty()) return prompt to emptyList()
        val images = if (HertzBrain.canSee) {
            files.filter { it.isImage }.take(HertzBrain.MAX_IMAGES).mapNotNull { Uploads.imageFor(it.file) }
        } else emptyList()
        val home = android.os.Environment.getExternalStorageDirectory().path + "/"
        val list = files.joinToString("\n") { f ->
            val kind = when { f.isImage -> "photo"; f.isPdf -> "PDF"; else -> f.mime }
            "- ${f.file.path.removePrefix(home)} ($kind, ${sizeText(f.file.length())})"
        }
        val note = buildString {
            append(prompt.ifBlank { "Take a look at what I've attached." })
            append("\n\n[The user attached these files; they are saved on the phone at:\n$list\n")
            if (images.isNotEmpty()) append("The photos are shown above. ")
            append("Use read_file for text files, look_at_file for photos and PDF pages, and the other file tools as needed.]")
        }
        return note to images
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${(bytes + 1023) / 1024} KB"
    }

    /** If the model finishes without saying anything, describe the actions it took. */
    private fun fallbackSummary(): String {
        val done = ChatStore.actionsThisTask.toList()
            .filter { it.firstOrNull()?.isUpperCase() == true && !it.endsWith("…") }
            .filterNot { it.startsWith("Reading") || it.startsWith("Looking") || it.startsWith("Checking") }
        return if (done.isEmpty()) "All done, sir."
        else "All done, sir. " + done.takeLast(4).joinToString(" ")
    }

    fun suggest() = submit(
        "Call read_screen, then suggest three short, useful things you could do for me right now " +
            "based on what is on my screen. Do not act yet.",
    )

    fun stop() {
        HertzBrain.stop()
        Voice.stopSpeaking()
        ChatStore.status.value = "Stopped."
    }

    fun newChat() {
        stop()
        ChatStore.clear()
        scope.launch { HertzBrain.newChat() }
    }
}
