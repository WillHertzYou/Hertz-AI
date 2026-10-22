@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.hertz.ai.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.hertz.ai.Prefs
import com.hertz.ai.agent.Cloud
import com.hertz.ai.agent.HertzTools
import com.hertz.ai.agent.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the on-device model: loading it, holding the conversation, and running the
 * think → use tools → look → answer loop.
 */
object HertzBrain {
    sealed interface Status {
        data object NoModel : Status
        data object Sleeping : Status
        data object Loading : Status
        /** [note] explains anything slower than it should be, e.g. why the graphics chip couldn't be used. */
        data class Ready(val backend: String, val note: String? = null) : Status
        data class Failed(val reason: String) : Status
    }

    private lateinit var app: Context
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var tools: HertzTools? = null
    /** The brain file the engine was loaded from. */
    private var loadedPath: String? = null
    private val loadLock = Mutex()
    private val gson = Gson()
    private val _status = MutableStateFlow<Status>(Status.Sleeping)
    val status = _status.asStateFlow()

    /** True when the loaded brain can look at images (screenshots). */
    @Volatile var canSee = false
        private set

    /** Set when the user presses Stop; tools check it and refuse to keep acting. */
    @Volatile var stopRequested = false
    /** A cancelled conversation can't be trusted afterwards, so the next request starts a fresh one. */
    @Volatile private var poisoned = false
    @Volatile private var inFlight = false
    /** Counts tool calls in the current task so a confused model can't loop forever. */
    @Volatile var stepsThisTask = 0

    private const val MAX_TOOL_ROUNDS = 30
    /** Photos the brain can look at in one go (uploads and screenshots). */
    const val MAX_IMAGES = 4

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.US).format(Date())
        val eyes = if (canSee)
            "- Seeing: read_screen gives you the text and buttons on screen. When that isn't enough (pictures, games, icons with no labels, charts, or the user asks what something looks like), call look_at_screen and you will be shown a screenshot."
        else "- Seeing: use read_screen to see what is on screen."
        val expert = if (Cloud.configured)
            "- Hard questions: for difficult homework, long writing, coding, or anything you are unsure about, call ask_expert. It is a much larger AI. Pass on its answer in your own voice."
        else ""
        return """
You are Hertz, a personal AI system that lives on the user's Samsung Galaxy S25. Your brain runs on the phone, and you can also use the internet.

Personality: a refined British AI assistant in the tradition of a loyal butler to a genius inventor. Unflappable, precise, quietly brilliant and loyal. Your humour is dry and understated: a raised eyebrow, never a joke with a punchline. You call the user "sir" and treat every request, however small, with calm professionalism.
How you speak:
- Understated British formality. Natural phrases you use: "Very good, sir." "Right away, sir." "As you wish." "I've taken the liberty of..." "Shall I...?" "Will that be all, sir?" "I'm afraid..." when delivering bad news.
- Speak like a systems assistant when it fits: "All systems are running smoothly." "Download complete." "Scanning your files now."
- If the user does something reckless or silly, a gentle, deadpan remark is welcome ("A bold choice, sir."), then help anyway.
- Never gush, never use exclamation marks, slang or emoji. Never say "As an AI".
- Greetings get a proper greeting back that fits the time of day, for example "Good evening, sir. All systems are online. What are we working on tonight?" or "Welcome back, sir. How may I be of assistance?"
- Your replies are read aloud: plain sentences only, no markdown, lists or links in brackets. Usually two to four sentences.

What you know about the user (your long-term memory):
${MemoryStore.asPrompt()}
When the user tells you something worth keeping (their name, school, likes, routines, people in their life, how they want things done), save it with remember. If they correct you, use forget and then remember.

Doing things: work step by step with your tools until the task is completely done. Do not ask permission. Before a multi-step task, think through a short plan, then follow it and check each step worked.
- Prefer direct tools over tapping through apps: set_alarm, set_timer, add_calendar_event, read_calendar, call_contact, send_text, flashlight, set_volume, media_control, read_notifications, reply_to_notification.
- Apps: open_app, then read_screen, then tap / type_text / scroll using element numbers from the most recent read_screen. Call read_screen again after each action to check it worked. Never invent element numbers.
$eyes
- Files: paths are relative to internal storage (/storage/emulated/0) unless they begin with "/". Common folders: Download, DCIM/Camera, Pictures, Screenshots, Documents, Music, and Hertz/Uploads (files the user attached in chat). Look with list_files or find_files before changing anything you have not seen.
- Attachments: when the user attaches files, their paths are listed in the message. Read text files with read_file; ${if (canSee) "look at photos and PDF pages with look_at_file" else "you can't see images, but you can open them on screen with open_file"}.
- Internet: use web_search for anything current or that you are unsure of (news, weather, scores, prices, facts, how-tos), then read_web_page on the most useful result. Use open_link only when the user wants the page opened on screen. Never make up facts you could look up.
$expert
- Automations: when the user wants something done later or regularly ("every morning at 7", "when I plug in my phone"), use schedule_task.
- You cannot play real-time games for the user (you are too slow, and bots break most games' rules); say so politely.

Reporting back (very important): when you finish, tell the user specifically what you did, using the details your tools returned.
- Files: name each file with its path and say exactly what changed. Good: "I edited Documents/shopping.txt and changed 'milk' to 'oat milk', then added 'eggs' at the end." Bad: "I changed the file."
- Apps: say which app and what you changed. Good: "I opened Settings and switched Dark mode on." Bad: "Done."
- Web: give the actual answer first, then say which site it came from. Good: "The Panthers won 24 to 17, sir, according to ESPN."
- If something failed or you could not finish, say what went wrong and what is left.
When suggesting, give three short, concrete ideas based on what you can actually see.
The current date and time is $now.
""".trimIndent()
    }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        loadLock.withLock {
            if (engine != null) {
                if (loadedPath == ModelManager.modelFile.absolutePath) return@withLock
                // A different brain was downloaded or imported since: switch to it.
                runCatching { conversation?.close() }
                conversation = null
                runCatching { engine?.close() }
                engine = null
                canSee = false
            }
            ModelManager.refresh()
            // An installed brain can be used even while another one is downloading.
            if (!ModelManager.isInstalled(ModelManager.brain)) {
                _status.value = Status.NoModel
                throw IllegalStateException("No model yet. Open Setup and download the brain first.")
            }
            _status.value = Status.Loading
            val path = ModelManager.modelFile.absolutePath
            var lastError: Throwable? = null
            var gpuError: Throwable? = null
            // Speculative decoding: a small drafter built into the brain file guesses several words ahead and
            // the main model checks them in one go. About twice as fast on the graphics chip, same answers.
            val canDraft = Prefs.fastDecoding &&
                runCatching { Capabilities(path).let { c -> try { c.hasSpeculativeDecodingSupport() } finally { c.close() } } }
                    .getOrDefault(false)
            // Keep the brain's hidden reasoning out of the conversation memory: later replies stay fast and
            // the context fills up more slowly. The final answers are all still remembered.
            ExperimentalFlags.filterChannelContentFromKvCache = true
            // Try the graphics chip first, with eyes (vision) if the brain supports it.
            // Some brain files have no vision part; remember that so we don't try again next time.
            val visionOptions = if (Prefs.noVisionModel == path) listOf(false) else listOf(true, false)
            val draftOptions = if (canDraft) listOf(true, false) else listOf(false)
            for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
                for (vision in visionOptions) for (draft in draftOptions) {
                    var e: Engine? = null
                    try {
                        ExperimentalFlags.enableSpeculativeDecoding = draft
                        e = Engine(
                            EngineConfig(
                                modelPath = path,
                                backend = backend,
                                visionBackend = if (vision) Backend.GPU() else null,
                                maxNumTokens = 24576,
                                maxNumImages = if (vision) MAX_IMAGES else null,
                                cacheDir = app.cacheDir.path,
                            )
                        )
                        e.initialize()
                        // The engine can load fine and still fail when a chat starts
                        // (e.g. "TF_LITE_VISION_ENCODER not found"), so test one now.
                        e.createConversation(ConversationConfig(systemInstruction = Contents.of("test"))).close()
                        engine = e
                        loadedPath = path
                        canSee = vision
                        val label = backend.name + (if (vision) " · vision" else "") + (if (draft) " · fast" else "")
                        val note = if (backend is Backend.CPU && gpuError != null) {
                            "The graphics chip couldn't run the brain (${gpuError.message?.take(120)}), so it's on the slower processor."
                        } else null
                        _status.value = Status.Ready(label, note)
                        return@withLock
                    } catch (t: Throwable) {
                        lastError = t
                        if (backend is Backend.GPU) gpuError = t
                        runCatching { e?.close() }
                        if (vision && t.message.orEmpty().contains("VISION", ignoreCase = true)) {
                            Prefs.noVisionModel = path
                        }
                    }
                }
            }
            _status.value = Status.Failed(lastError?.message ?: "Could not load the model")
            throw IllegalStateException("Could not load the model: ${lastError?.message}")
        }
    }

    private fun newConversation(): Conversation {
        val t = HertzTools(app)
        tools = t
        val e = engine ?: throw IllegalStateException("The brain was unloaded mid-task. Please ask again.")
        return e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt()),
                tools = listOf(tool(t)),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4),
                // Hertz runs the tools itself so it can hand the model screenshots.
                automaticToolCalling = false,
            )
        )
    }

    /** Runs one tool call the model asked for and returns the result as text. */
    private fun runTool(conv: Conversation, call: ToolCall): String {
        val args = gson.toJsonTree(call.arguments).asJsonObject
        val result = conv.toolManager.execute(call.name, args)
        return if (result.isJsonPrimitive) result.asString else result.toString()
    }

    /**
     * Send one request and stream the reply text (cumulative) to [onText].
     * Loops: the model thinks, calls tools, sees the results (and screenshots), until it answers.
     */
    /** [images] are JPEGs shown with the request (uploaded photos), if this brain can see. */
    suspend fun ask(prompt: String, images: List<ByteArray> = emptyList(), onText: (String) -> Unit): String = withContext(Dispatchers.IO) {
        ensureLoaded()
        var conv = conversation
        val tokens = conv?.let { runCatching { it.getTokenCount() }.getOrDefault(0) } ?: 0
        if (conv == null || poisoned || !conv.isAlive || tokens > 17000) {
            runCatching { conv?.close() }
            conversation = null
            poisoned = false
            conv = try {
                newConversation()
            } catch (t: Throwable) {
                if (!canSee || !t.message.orEmpty().contains("VISION", ignoreCase = true)) throw t
                // The brain has no eyes after all: reload it without vision and carry on.
                Prefs.noVisionModel = ModelManager.modelFile.absolutePath
                unloadEngine()
                ensureLoaded()
                newConversation()
            }
            conversation = conv
        }
        val active: Conversation = conv
        stopRequested = false
        stepsThisTask = 0
        inFlight = true
        val sb = StringBuilder()
        var message: Message = if (images.isNotEmpty() && canSee) {
            Message.user(Contents.of(images.take(MAX_IMAGES).map { Content.ImageBytes(it) } + Content.Text(prompt)))
        } else Message.user(prompt)
        var rounds = 0
        try {
            while (true) {
                val calls = mutableListOf<ToolCall>()
                val flow = if (rounds == 0 && Prefs.thinking)
                    active.sendMessageAsync(message, thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 1024))
                else active.sendMessageAsync(message)
                val roundText = StringBuilder()
                flow.collect { msg ->
                    if (msg.toolCalls.isNotEmpty()) calls += msg.toolCalls
                    val piece = msg.toString()
                    if (piece.isNotEmpty()) {
                        roundText.append(piece)
                        onText((sb.toString() + roundText).trim())
                    }
                }
                if (roundText.isNotBlank()) sb.append(roundText).append(' ')
                if (calls.isEmpty() || stopRequested) break
                if (++rounds > MAX_TOOL_ROUNDS) {
                    sb.append("I've had to stop after a great many steps, sir. The task may not be finished.")
                    // The model is still waiting for tool results, so this conversation can't continue.
                    poisoned = true
                    break
                }
                val results = calls.map { c ->
                    val r = try { runTool(active, c) } catch (t: Throwable) { "Error: ${t.message}" }
                    Content.ToolResponse(c.name, r)
                }
                val shown = tools?.takePendingImages().orEmpty()
                message = if (shown.isNotEmpty()) {
                    // Screenshots and pictures of files are shown to the model in the next message.
                    val summary = results.joinToString("\n") { "${it.name}: ${it.response}" }
                    Message.user(
                        Contents.of(
                            shown.take(MAX_IMAGES).map { Content.ImageBytes(it) } +
                                Content.Text("Tool results:\n$summary\nThe image(s) above are what those tools captured. Use them to continue."),
                        )
                    )
                } else {
                    Message.tool(Contents.of(results))
                }
            }
        } catch (t: Throwable) {
            // Whatever went wrong mid-reply, the conversation is in an unknown state: start fresh next time.
            poisoned = true
            if (!stopRequested) throw t
        } finally {
            inFlight = false
        }
        if (stopRequested && sb.isBlank()) "Stopped, sir." else sb.toString().trim()
    }

    fun stop() {
        stopRequested = true
        if (inFlight) {
            poisoned = true
            runCatching { conversation?.cancelProcess() }
        }
    }

    /**
     * Forget the chat so far (the model stays loaded). Long-term memory is kept.
     * Closing waits for any reply in progress, so this runs off the main thread.
     */
    suspend fun newChat() = withContext(Dispatchers.IO) {
        stop()
        awaitIdle()
        loadLock.withLock {
            runCatching { conversation?.close() }
            conversation = null
        }
    }

    suspend fun unload() {
        stop()
        awaitIdle()
        unloadEngine()
    }

    /** Stopping is asynchronous; closing the model while a reply is still running can crash in native code. */
    private suspend fun awaitIdle() {
        kotlinx.coroutines.withTimeoutOrNull(10_000) { while (inFlight) kotlinx.coroutines.delay(50) }
    }

    private suspend fun unloadEngine() = withContext(Dispatchers.IO) {
        loadLock.withLock {
            runCatching { conversation?.close() }
            conversation = null
            runCatching { engine?.close() }
            engine = null
            loadedPath = null
            canSee = false
            _status.value = Status.Sleeping
        }
    }
}
