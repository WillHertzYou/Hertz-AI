package com.hertz.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hertz.ai.CrashLog
import com.hertz.ai.Prefs
import com.hertz.ai.agent.Cloud
import com.hertz.ai.device.HertzAccessibilityService
import com.hertz.ai.device.HertzNotificationListener
import com.hertz.ai.llm.HertzBrain
import com.hertz.ai.llm.ModelManager
import com.hertz.ai.voice.NeuralVoice
import com.hertz.ai.voice.Voice
import com.hertz.ai.voice.WakeWordService
import kotlinx.coroutines.launch

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++; ModelManager.refresh()
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().background(HertzColors.Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HertzLogo(44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Setup", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = HertzColors.Ink)
                Text("Three steps and Hertz is fully awake.", color = HertzColors.Muted, fontSize = 14.sp)
            }
        }
        CrashCard()
        ModelCard()
        PermissionsCard(ctx, tick)
        VoiceCard()
        SmarterCard()
        Text(
            "The AI model runs entirely on this phone. Hertz goes online only to download its brain once, and when you ask it to search the web or read a page.",
            color = HertzColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

/** Shown after Hertz closed unexpectedly, so the details can be copied and reported. */
@Composable
private fun CrashCard() {
    val report by CrashLog.last.collectAsState()
    val text = report ?: return
    val ctx = LocalContext.current
    Card("Hertz closed unexpectedly", "!!") {
        Text(
            text.lineSequence().take(14).joinToString("\n"),
            color = HertzColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = Mono,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Hertz crash report", text))
                    android.widget.Toast.makeText(ctx, "Copied the crash report", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = HertzColors.Cyan, contentColor = Color.Black),
            ) { Text("Copy report") }
            TextButton(onClick = { CrashLog.dismiss() }) { Text("Dismiss", color = HertzColors.Muted) }
        }
    }
}

@Composable
private fun Card(title: String, step: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(HertzColors.Surface)
            .border(1.dp, HertzColors.Line, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(step, color = HertzColors.Cyan, fontFamily = Mono, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = HertzColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

private fun gb(b: Long) = "%.2f GB".format(b / 1_073_741_824.0)

@Composable
private fun ModelCard() {
    val state by ModelManager.state.collectAsState()
    val brain by HertzBrain.status.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ModelManager.import(uri)
    }
    val primary = ButtonDefaults.buttonColors(containerColor = HertzColors.Cyan, contentColor = Color.Black)

    Card("Brain", "01") {
        Text(
            "Runs offline on the S25's graphics chip. It downloads once; after that Hertz works without it.",
            color = HertzColors.Muted, fontSize = 13.sp,
        )
        when (val s = state) {
            is ModelManager.State.Downloading -> {
                val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                Text("Downloading ${s.brain.lowercase()}", color = HertzColors.Ink, fontSize = 14.sp)
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = HertzColors.Cyan, trackColor = HertzColors.Line)
                s.note?.let { Text(it, color = HertzColors.Warn, fontSize = 13.sp) }
                Text(
                    "${gb(s.done)} of ${if (s.total > 0) gb(s.total) else "…"}. You can leave the app; it keeps going in the background. " +
                        "Wi-Fi is much faster than mobile data.",
                    color = HertzColors.Muted, fontSize = 13.sp,
                )
                TextButton(onClick = { ModelManager.cancelDownload() }) { Text("Cancel", color = HertzColors.Muted) }
            }
            is ModelManager.State.Importing -> {
                val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = HertzColors.Cyan, trackColor = HertzColors.Line)
                Text("Copying ${gb(s.done)}…", color = HertzColors.Muted, fontSize = 13.sp)
            }
            else -> {
                if (s is ModelManager.State.Failed) Text(s.reason, color = HertzColors.Bad, fontSize = 13.sp)
                if (s is ModelManager.State.Ready) {
                    StatusLine(true, when (val b = brain) {
                        is HertzBrain.Status.Ready -> "${ModelManager.brain.title} loaded (${b.backend})" + (b.note?.let { "\n$it" } ?: "")
                        is HertzBrain.Status.Loading -> "${ModelManager.brain.title} · loading…"
                        is HertzBrain.Status.Failed -> "${ModelManager.brain.title} installed, but loading failed: ${b.reason}"
                        else -> "${ModelManager.brain.title} installed · wakes when you first talk to Hertz"
                    })
                }
                ModelManager.choices.forEach { choice ->
                    val have = ModelManager.isInstalled(choice)
                    val active = have && ModelManager.brain.id == choice.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(if (active) HertzColors.Cyan.copy(alpha = 0.10f) else HertzColors.SurfaceHigh)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${choice.title} · ${choice.gb} GB", color = HertzColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(choice.detail, color = HertzColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            active -> Text("In use", color = HertzColors.Cyan, fontSize = 13.sp)
                            have -> TextButton(onClick = { ModelManager.use(choice) }) { Text("Use", color = HertzColors.Cyan) }
                            else -> Button(onClick = { ModelManager.download(choice) }, colors = primary) { Text("Get") }
                        }
                    }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("I already have a .litertlm file", color = HertzColors.Ink)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (ok) HertzColors.Good else HertzColors.Muted, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = HertzColors.Ink, fontSize = 14.sp)
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun PermissionsCard(ctx: Context, tick: Int) {
    val pkgUri = Uri.parse("package:${ctx.packageName}")
    val screenOn = HertzAccessibilityService.instance != null
    val filesOn = Environment.isExternalStorageManager()
    val micOn = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val batteryOn = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    val alarmsOn = ctx.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
    val notifOn = HertzNotificationListener.isEnabled(ctx)
    val phonePerms = arrayOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
    )
    val phoneOn = phonePerms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Card("Permissions", "02") {
        PermissionRow(
            "Screen control + overlay",
            "Accessibility › Installed apps › Hertz agent. If it's greyed out, use “App info” below, tap ⋮ › Allow restricted settings, then try again.",
            screenOn,
        ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        PermissionRow("All files access", "Lets Hertz see, create and edit any file on the phone and USB drives.", filesOn) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri))
        }
        PermissionRow("Notifications", "Lets Hertz read your notifications and reply to messages for you.", notifOn) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        PermissionRow("Calls, texts, contacts & calendar", "So Hertz can call or text people and manage your calendar directly.", phoneOn) {
            askPhone.launch(phonePerms)
        }
        PermissionRow("Microphone", "For talking to Hertz.", micOn) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
        }
        PermissionRow("Alarms & reminders", "So scheduled tasks start on time, even when Hertz is in the background.", alarmsOn) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
        }
        PermissionRow("Stay awake in background", "Stops Samsung from putting Hertz to sleep mid-task.", batteryOn) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
        }
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }) {
            Text("Open Hertz App info", color = HertzColors.Cyan)
        }
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, granted: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !granted, onClick = onFix)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (granted) HertzColors.Good else HertzColors.Muted, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = HertzColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = HertzColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(CircleShape).background(HertzColors.Cyan.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("Allow", color = HertzColors.Cyan, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun VoiceCard() {
    val voices by Voice.voices.collectAsState()
    var speak by remember { mutableStateOf(Prefs.speakReplies) }
    var chosen by remember { mutableStateOf(Prefs.voiceName ?: voices.firstOrNull()) }
    var open by remember { mutableStateOf(false) }
    var orb by remember { mutableStateOf(Prefs.showOrb) }
    val neural by NeuralVoice.state.collectAsState()
    var useNeural by remember { mutableStateOf(Prefs.neuralVoice) }
    var speaker by remember { mutableIntStateOf(Prefs.neuralSpeaker) }
    var effect by remember { mutableStateOf(Prefs.aiVoiceEffect) }
    val ctx = LocalContext.current
    var wake by remember { mutableStateOf(Prefs.wakeWord) }
    val wakeStatus by WakeWordService.status.collectAsState()

    Card("Voice & overlay", "03") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speak replies", color = HertzColors.Ink, fontSize = 15.sp)
                Text("British butler voice", color = HertzColors.Muted, fontSize = 12.sp)
            }
            Switch(speak, { speak = it; Prefs.speakReplies = it }, colors = SwitchDefaults.colors(checkedTrackColor = HertzColors.Cyan))
        }
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (voices.isEmpty()) "No British voices found · install Google Speech Services"
                    else "Voice: ${chosen ?: voices.first()}",
                    color = HertzColors.Ink, fontSize = 13.sp,
                )
            }
            DropdownMenu(open, { open = false }) {
                voices.forEach { v ->
                    DropdownMenuItem(text = { Text(v) }, onClick = {
                        chosen = v; open = false; Voice.setVoice(v); Voice.previewPhoneVoice("At your service, sir.")
                    })
                }
            }
        }
        // ---- Neural voice ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Natural AI voice", color = HertzColors.Ink, fontSize = 15.sp)
                Text(
                    when (val n = neural) {
                        is NeuralVoice.State.Downloading -> "Downloading… ${n.mb} of about ${NeuralVoice.SIZE_MB} MB"
                        is NeuralVoice.State.Failed -> n.reason
                        NeuralVoice.State.Ready -> "Free, runs on the phone, no internet needed."
                        else -> "A free, lifelike British voice that runs on the phone. One-time ${NeuralVoice.SIZE_MB} MB download."
                    },
                    color = if (neural is NeuralVoice.State.Failed) HertzColors.Bad else HertzColors.Muted,
                    fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            if (neural == NeuralVoice.State.Ready) {
                Switch(useNeural, { useNeural = it; Prefs.neuralVoice = it }, colors = SwitchDefaults.colors(checkedTrackColor = HertzColors.Cyan))
            } else if (neural !is NeuralVoice.State.Downloading) {
                Button(
                    onClick = { NeuralVoice.download() },
                    colors = ButtonDefaults.buttonColors(containerColor = HertzColors.Cyan, contentColor = Color.Black),
                ) { Text("Get") }
            }
        }
        if (neural == NeuralVoice.State.Ready && useNeural) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeuralVoice.speakers.forEach { (id, name) ->
                    val on = speaker == id
                    Box(
                        Modifier.clip(CircleShape)
                            .background(if (on) HertzColors.Cyan.copy(alpha = 0.18f) else HertzColors.SurfaceHigh)
                            .clickable {
                                speaker = id; Prefs.neuralSpeaker = id
                                Voice.speak("Good evening, sir. This is how I sound as $name.")
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(name, color = if (on) HertzColors.Cyan else HertzColors.Ink, fontSize = 13.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI system effect", color = HertzColors.Ink, fontSize = 15.sp)
                    Text("A subtle echo so it sounds like a computer system talking.", color = HertzColors.Muted, fontSize = 12.sp)
                }
                Switch(effect, { effect = it; Prefs.aiVoiceEffect = it }, colors = SwitchDefaults.colors(checkedTrackColor = HertzColors.Cyan))
            }
        }
        OutlinedButton(onClick = { Voice.speak("Good day, sir. All systems are online and at your service.") }, modifier = Modifier.fillMaxWidth()) {
            Text("Test voice", color = HertzColors.Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("“Hey Hertz”", color = HertzColors.Ink, fontSize = 15.sp)
                Text(
                    if (wake || wakeStatus != "Off") wakeStatus else "Say “Hey Hertz” any time, even with the screen off. Works offline; uses a little battery.",
                    color = HertzColors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Switch(wake, {
                if (it) wake = WakeWordService.start(ctx) else { wake = false; WakeWordService.stop(ctx) }
            }, colors = SwitchDefaults.colors(checkedTrackColor = HertzColors.Cyan))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Floating orb", color = HertzColors.Ink, fontSize = 15.sp)
                Text("Tap to command · hold to talk · drag to move", color = HertzColors.Muted, fontSize = 12.sp)
            }
            Switch(orb, {
                orb = it; Prefs.showOrb = it; HertzAccessibilityService.instance?.setOverlayVisible(it)
            }, colors = SwitchDefaults.colors(checkedTrackColor = HertzColors.Cyan))
        }
    }
}

@Composable
private fun SmarterCard() {
    var thinking by remember { mutableStateOf(Prefs.thinking) }
    var fast by remember { mutableStateOf(Prefs.fastDecoding) }
    var provider by remember { mutableStateOf(Prefs.cloudProvider) }
    var key by remember { mutableStateOf(Prefs.cloudKey) }
    var model by remember { mutableStateOf(Prefs.cloudModel) }
    var base by remember { mutableStateOf(Prefs.cloudBaseUrl) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = HertzColors.Cyan, unfocusedBorderColor = HertzColors.Line, cursorColor = HertzColors.Cyan,
        focusedTextColor = HertzColors.Ink, unfocusedTextColor = HertzColors.Ink,
    )

    Card("Smarter", "04") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Think before acting", color = HertzColors.Ink, fontSize = 15.sp)
                Text(
                    "Hertz plans each task step by step before doing it. A little slower, far fewer mistakes.",
                    color = HertzColors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Switch(thinking, { thinking = it; Prefs.thinking = it }, colors = SwitchDefaults.colors(checkedTrackColor = HertzColors.Cyan))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Faster replies", color = HertzColors.Ink, fontSize = 15.sp)
                Text(
                    "Speculative decoding on the graphics chip: a tiny helper model guesses ahead so Hertz talks about twice as fast. Same answers.",
                    color = HertzColors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Switch(fast, {
                fast = it; Prefs.fastDecoding = it
                // Takes effect when the brain next loads.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { HertzBrain.unload() }
            }, colors = SwitchDefaults.colors(checkedTrackColor = HertzColors.Cyan))
        }
        Text("Expert brain (optional)", color = HertzColors.Ink, fontSize = 15.sp)
        Text(
            "For hard questions, homework, long writing or code, Hertz can ask a much bigger AI online. Needs your own API key and costs a little per question.",
            color = HertzColors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("claude" to "Claude", "openai" to "OpenAI-compatible").forEach { (id, label) ->
                val on = provider == id
                Box(
                    Modifier.clip(CircleShape)
                        .background(if (on) HertzColors.Cyan.copy(alpha = 0.18f) else HertzColors.SurfaceHigh)
                        .clickable { provider = id; Prefs.cloudProvider = id }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(label, color = if (on) HertzColors.Cyan else HertzColors.Ink, fontSize = 13.sp) }
            }
        }
        OutlinedTextField(
            key, { key = it; Prefs.cloudKey = it }, label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            model, { model = it; Prefs.cloudModel = it },
            label = { Text(if (provider == "claude") "Model (default ${Cloud.DEFAULT_CLAUDE_MODEL})" else "Model name") },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        if (provider == "openai") {
            OutlinedTextField(
                base, { base = it; Prefs.cloudBaseUrl = it }, label = { Text("API address") }, singleLine = true,
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            if (key.isBlank()) "Off: Hertz answers everything with its on-phone brain."
            else "On: Hertz will ask the expert when a question is too hard. Start a new chat for it to take effect.",
            color = HertzColors.Muted, fontSize = 12.sp,
        )
    }
}
