package com.hertz.ai

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hertz.ai.agent.ChatStore
import com.hertz.ai.llm.ModelManager
import com.hertz.ai.ui.ChatScreen
import com.hertz.ai.ui.HertzColors
import com.hertz.ai.ui.HertzTheme
import com.hertz.ai.ui.MemoryScreen
import com.hertz.ai.ui.SetupScreen
import com.hertz.ai.voice.Voice
import com.hertz.ai.voice.WakeWordService

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Greets you out loud when you open Hertz (at most every 30 minutes).
        Voice.greet()?.let { ChatStore.status.value = it }
        // Keep "Hey Hertz" running if it was switched on (Android needs the app open to start it).
        // Also retry after a failure (e.g. the wake-word model couldn't download while offline).
        val wakeStatus = WakeWordService.status.value
        val wakeDown = wakeStatus == "Off" || wakeStatus.startsWith("Wake word failed") || wakeStatus.startsWith("Open Hertz") ||
            wakeStatus.startsWith("Couldn't start")
        if (Prefs.wakeWord && wakeDown &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) WakeWordService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(
            arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            ),
            1,
        )
        // Open Setup when there's no brain yet, or to show what went wrong if Hertz crashed last time.
        val startTab = if (ModelManager.state.value == ModelManager.State.Ready && CrashLog.last.value == null) 0 else 2
        setContent {
            HertzTheme {
                var tab by rememberSaveable { mutableIntStateOf(startTab) }
                Scaffold(
                    containerColor = HertzColors.Bg,
                    bottomBar = {
                        NavigationBar(containerColor = HertzColors.Surface) {
                            val colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = HertzColors.Cyan,
                                selectedTextColor = HertzColors.Cyan,
                                indicatorColor = HertzColors.Cyan.copy(alpha = 0.14f),
                                unselectedIconColor = HertzColors.Muted,
                                unselectedTextColor = HertzColors.Muted,
                            )
                            listOf(
                                Triple("Chat", Icons.Outlined.ChatBubbleOutline, 0),
                                Triple("Memory", Icons.Outlined.Psychology, 1),
                                Triple("Setup", Icons.Outlined.Tune, 2),
                            ).forEach { (label, icon, i) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, label) },
                                    label = { Text(label) },
                                    colors = colors,
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                        when (tab) {
                            0 -> ChatScreen(onOpenSetup = { tab = 2 })
                            1 -> MemoryScreen()
                            else -> SetupScreen()
                        }
                    }
                }
            }
        }
    }
}
