package com.hertz.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hertz.ai.agent.Automations
import com.hertz.ai.agent.MemoryStore

/** What Hertz remembers about you, and the jobs it does by itself on a schedule. */
@Composable
fun MemoryScreen() {
    val facts by MemoryStore.facts.collectAsState()
    val tasks by Automations.list.collectAsState()
    var newFact by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().background(HertzColors.Bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Memory", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = HertzColors.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Hertz remembers these things about you in every chat. Tell it “remember that…” or add one here.",
                color = HertzColors.Muted, fontSize = 14.sp,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    newFact, { newFact = it },
                    placeholder = { Text("e.g. My name is William") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HertzColors.Cyan, unfocusedBorderColor = HertzColors.Line,
                        cursorColor = HertzColors.Cyan, focusedTextColor = HertzColors.Ink, unfocusedTextColor = HertzColors.Ink,
                    ),
                )
                TextButton(onClick = { if (MemoryStore.add(newFact)) newFact = "" }) { Text("Add", color = HertzColors.Cyan) }
            }
        }
        if (facts.isEmpty()) item { Empty("Nothing remembered yet.") }
        items(facts, key = { "f:$it" }) { f -> Line(Icons.Outlined.Lightbulb, f, null) { MemoryStore.remove(f) } }

        item {
            Spacer(Modifier.height(18.dp))
            Text("Scheduled tasks", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = HertzColors.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Things Hertz does by itself. Just ask, for example: “Every weekday at 7am, tell me the weather and what's on my calendar” or “When I plug in my phone, read me my notifications.”",
                color = HertzColors.Muted, fontSize = 14.sp,
            )
        }
        if (tasks.isEmpty()) item { Empty("No scheduled tasks yet.") }
        items(tasks, key = { "t:${it.id}" }) { t -> Line(Icons.Outlined.Bolt, t.instruction, t.describe()) { Automations.remove(t.id) } }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = HertzColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun Line(icon: ImageVector, text: String, sub: String?, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HertzColors.Surface)
            .border(1.dp, HertzColors.Line, RoundedCornerShape(16.dp)).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = HertzColors.Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (sub != null) Text(sub, color = HertzColors.Cyan, fontSize = 12.sp, fontFamily = Mono)
            Text(text, color = HertzColors.Ink, fontSize = 14.sp, lineHeight = 19.sp)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Delete", tint = HertzColors.Muted) }
    }
}
