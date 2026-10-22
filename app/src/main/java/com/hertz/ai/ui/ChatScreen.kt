package com.hertz.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import com.hertz.ai.agent.Uploads
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hertz.ai.agent.Agent
import com.hertz.ai.agent.ChatMsg
import com.hertz.ai.agent.ChatStore
import com.hertz.ai.agent.Role
import com.hertz.ai.llm.HertzBrain
import com.hertz.ai.voice.Voice
import java.util.Calendar

@Composable
fun ChatScreen(onOpenSetup: () -> Unit) {
    val messages by ChatStore.messages.collectAsState()
    val busy by Agent.busy.collectAsState()
    val brain by HertzBrain.status.collectAsState()
    val listening by Voice.listening.collectAsState()
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val attachments by Uploads.pending.collectAsState()
    val copying = attachments.any { it.saved == null && it.error == null }
    // Any kind of file, as many as you like; photos are picked from the same screen.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Uploads.add(uris)
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }

    fun send(text: String) {
        val hasFiles = attachments.any { it.saved != null }
        if (text.isBlank() && !hasFiles) return
        if (Uploads.busy) {
            ChatStore.status.value = "Still copying your files, one moment…"
            return
        }
        Agent.submit(text.trim(), files = Uploads.take())
        draft = ""
    }

    Column(Modifier.fillMaxSize().background(HertzColors.Bg).imePadding()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HertzLogo(34.dp, glow = false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Hertz", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = HertzColors.Ink)
                StatusPill(brain, busy, onOpenSetup)
            }
            IconButton(onClick = { Agent.newChat() }) {
                Icon(Icons.Outlined.AddComment, "New chat", tint = HertzColors.Muted)
            }
        }

        // Conversation
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) EmptyState(onPick = ::send)
            else LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { MessageRow(it, busy && it == messages.last()) }
            }
        }

        // Attachments waiting to be sent
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { a -> AttachmentChip(a) { Uploads.remove(a.id) } }
            }
        }

        // Input bar
        Row(
            Modifier.fillMaxWidth().padding(12.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(HertzColors.Surface)
                .border(1.dp, HertzColors.Line, RoundedCornerShape(26.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(
                color = if (listening) HertzColors.Cyan else HertzColors.SurfaceHigh,
                onClick = {
                    Voice.listen(onPartial = { draft = it }) { heard ->
                        if (heard.isNotBlank()) send(heard)
                        else Voice.listenError.value?.let { ChatStore.status.value = it }
                    }
                },
            ) { Icon(Icons.Filled.Mic, "Talk", tint = if (listening) Color.Black else HertzColors.Ink) }
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.AttachFile, "Attach files or photos", tint = HertzColors.Muted)
            }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (listening) "Listening…" else "Ask Hertz to do anything…", color = HertzColors.Muted) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = HertzColors.Cyan,
                ),
            )
            if (busy) RoundButton(HertzColors.Bad, onClick = { Agent.stop() }) {
                Icon(Icons.Filled.Stop, "Stop", tint = Color.Black)
            } else RoundButton(if (copying) HertzColors.SurfaceHigh else HertzColors.Cyan, onClick = { send(draft) }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.Black)
            }
        }
    }
}

@Composable
private fun AttachmentChip(a: Uploads.Pending, onRemove: () -> Unit) {
    val status = when {
        a.error != null -> "failed: ${a.error}"
        a.saved == null -> if (a.size > 0) "copying ${(a.copied * 100 / a.size).coerceIn(0, 100)}%" else "copying…"
        else -> sizeLabel(a.saved.file.length())
    }
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(HertzColors.SurfaceHigh)
            .border(1.dp, if (a.error != null) HertzColors.Bad else HertzColors.Line, RoundedCornerShape(16.dp))
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AttachFile, null, tint = HertzColors.Cyan, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Column(Modifier.widthIn(max = 160.dp)) {
            Text(a.name, color = HertzColors.Ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = if (a.error != null) HertzColors.Bad else HertzColors.Muted, fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, "Remove", tint = HertzColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${(bytes + 1023) / 1024} KB"
}

@Composable
private fun StatusPill(brain: HertzBrain.Status, busy: Boolean, onOpenSetup: () -> Unit) {
    val (dot, label) = when {
        busy -> HertzColors.Cyan to "Working…"
        brain is HertzBrain.Status.Ready -> HertzColors.Good to "Online · on-device · ${brain.backend}"
        brain is HertzBrain.Status.Loading -> HertzColors.Warn to "Waking up…"
        brain is HertzBrain.Status.NoModel -> HertzColors.Warn to "No brain yet · tap to set up"
        brain is HertzBrain.Status.Failed -> HertzColors.Bad to "Couldn't load · tap for Setup"
        else -> HertzColors.Muted to "Asleep · wakes on first message"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = brain is HertzBrain.Status.NoModel || brain is HertzBrain.Status.Failed) { onOpenSetup() },
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = HertzColors.Muted)
    }
}

@Composable
private fun RoundButton(color: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MessageRow(m: ChatMsg, live: Boolean) {
    when (m.role) {
        Role.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                m.text,
                color = HertzColors.Ink,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(HertzColors.Blue.copy(alpha = 0.28f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Role.Hertz -> Row(verticalAlignment = Alignment.Top) {
            HertzLogo(26.dp, glow = false)
            Spacer(Modifier.width(8.dp))
            Text(
                m.text.ifEmpty { if (live) "…" else "" },
                color = HertzColors.Ink,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                    .background(HertzColors.Surface)
                    .border(1.dp, HertzColors.Line, RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Role.Action -> Row(
            Modifier.padding(start = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Bolt, null, tint = HertzColors.Cyan, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.text, color = HertzColors.Muted, fontSize = 12.sp, fontFamily = Mono)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HertzLogo(96.dp)
        Spacer(Modifier.height(18.dp))
        Text("$greeting, sir.", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HertzColors.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "All systems are online. I can run your apps, manage your files and search the web. How may I be of assistance?",
            color = HertzColors.Muted, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "What's on my screen?",
                "Tidy my Downloads folder",
                "Write a shopping list note",
                "Turn on Do not disturb",
                "What's in the news today?",
            ).forEach { s ->
                Text(
                    s, color = HertzColors.Ink, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(HertzColors.SurfaceHigh)
                        .border(1.dp, HertzColors.Line, RoundedCornerShape(18.dp))
                        .clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}
