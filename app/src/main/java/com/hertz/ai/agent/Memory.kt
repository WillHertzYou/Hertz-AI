package com.hertz.ai.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/** Long-term memory: facts about the user that Hertz keeps between chats. */
object MemoryStore {
    private lateinit var file: File
    private val _facts = MutableStateFlow<List<String>>(emptyList())
    val facts = _facts.asStateFlow()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "memory.json")
        _facts.value = runCatching {
            val a = JSONArray(file.readText()); (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<String>) {
        _facts.value = list
        file.writeText(JSONArray(list).toString(2))
    }

    fun add(fact: String): Boolean {
        val f = fact.trim().removeSuffix(".")
        if (f.isEmpty() || _facts.value.any { it.equals(f, true) }) return false
        save(_facts.value + f)
        return true
    }

    /** Removes every fact containing [text]; returns what was removed. */
    fun forget(text: String): List<String> {
        val gone = _facts.value.filter { it.contains(text.trim(), ignoreCase = true) }
        if (gone.isNotEmpty()) save(_facts.value - gone.toSet())
        return gone
    }

    fun remove(fact: String) = save(_facts.value - fact)

    fun asPrompt(): String =
        if (_facts.value.isEmpty()) "Nothing yet."
        else _facts.value.joinToString("\n") { "- $it" }
}

/**
 * Scheduled tasks: an instruction Hertz carries out by itself at a set time
 * ("every weekday at 7:00, tell me the weather") or when the phone starts charging.
 */
data class Automation(
    val id: String,
    val instruction: String,
    val trigger: String, // "time" or "charging"
    val hour: Int = 0,
    val minute: Int = 0,
    val repeat: String = "once", // once, daily, weekdays
) {
    fun describe(): String = when (trigger) {
        "charging" -> "When the phone starts charging"
        else -> {
            val h = if (hour % 12 == 0) 12 else hour % 12
            val time = "%d:%02d %s".format(h, minute, if (hour < 12) "AM" else "PM")
            when (repeat) { "daily" -> "Every day at $time"; "weekdays" -> "Weekdays at $time"; else -> "Once at $time" }
        }
    }
}

object Automations {
    private lateinit var app: Context
    private lateinit var file: File
    private val _list = MutableStateFlow<List<Automation>>(emptyList())
    val list = _list.asStateFlow()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        file = File(app.filesDir, "automations.json")
        _list.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Automation(
                    o.getString("id"), o.getString("instruction"), o.getString("trigger"),
                    o.optInt("hour"), o.optInt("minute"), o.optString("repeat", "once"),
                )
            }
        }.getOrDefault(emptyList())
        _list.value.forEach { schedule(it) }
    }

    private fun save(list: List<Automation>) {
        _list.value = list
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("instruction", it.instruction).put("trigger", it.trigger)
                    .put("hour", it.hour).put("minute", it.minute).put("repeat", it.repeat)
            )
        }
        file.writeText(a.toString(2))
    }

    fun addTimed(instruction: String, hour: Int, minute: Int, repeat: String): Automation {
        val a = Automation(UUID.randomUUID().toString().take(8), instruction.trim(), "time", hour, minute, repeat)
        save(_list.value + a)
        schedule(a)
        return a
    }

    fun addOnCharging(instruction: String): Automation {
        val a = Automation(UUID.randomUUID().toString().take(8), instruction.trim(), "charging")
        save(_list.value + a)
        return a
    }

    fun remove(id: String) {
        _list.value.firstOrNull { it.id == id }?.let { cancelAlarm(it) }
        save(_list.value.filterNot { it.id == id })
    }

    fun find(idOrText: String): Automation? =
        _list.value.firstOrNull { it.id == idOrText } ?: _list.value.firstOrNull { it.instruction.contains(idOrText, true) }

    private fun pending(a: Automation) = PendingIntent.getBroadcast(
        app, a.id.hashCode(),
        Intent(app, AutomationReceiver::class.java).putExtra("id", a.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun nextTime(a: Automation): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 5_000) c.add(Calendar.DAY_OF_YEAR, 1)
        if (a.repeat == "weekdays") {
            while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return c.timeInMillis
    }

    private fun schedule(a: Automation) {
        if (a.trigger != "time") return
        val am = app.getSystemService(AlarmManager::class.java)
        // Exact if the user allowed "Alarms & reminders" for Hertz, otherwise Android may run it up to 15 minutes late.
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime(a), pending(a))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime(a), pending(a))
    }

    private fun cancelAlarm(a: Automation) {
        if (a.trigger == "time") app.getSystemService(AlarmManager::class.java).cancel(pending(a))
    }

    /** Called by the alarm. Runs the task, then books the next time (or removes one-off tasks). */
    fun fire(id: String) {
        val a = _list.value.firstOrNull { it.id == id } ?: return
        if (a.repeat == "once") save(_list.value.filterNot { it.id == id }) else schedule(a)
        run(a)
    }

    fun onCharging() = _list.value.filter { it.trigger == "charging" }.forEach { run(it) }

    private fun run(a: Automation) {
        // Run inside a foreground service, otherwise Android kills the app while the brain loads.
        TaskService.start(app, "(This is a scheduled task you were asked to do: ${a.describe().lowercase()}.) ${a.instruction}")
    }
}

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("id")?.let { Automations.fire(it) }
    }
}

/** After a reboot or an update all alarms are gone, so this books them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Automations.init(context)
    }
}

/** Keeps Hertz alive (with a notification) while a scheduled task runs in the background. */
class TaskService : android.app.Service() {
    companion object {
        private const val CHANNEL = "hertz_tasks"
        fun start(ctx: Context, prompt: String) {
            val i = Intent(ctx, TaskService::class.java).putExtra("prompt", prompt)
            runCatching { ctx.startForegroundService(i) }
                .onFailure { Agent.submit(prompt) } // fall back to a plain background run
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prompt = intent?.getStringExtra("prompt")
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Scheduled tasks", android.app.NotificationManager.IMPORTANCE_LOW))
        val n = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.hertz.ai.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Hertz is doing a scheduled task")
            .setContentText(prompt?.substringAfter(".) ")?.take(80) ?: "")
            .setOngoing(true)
            .build()
        try {
            startForeground(8, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            if (prompt != null) Agent.submit(prompt)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (prompt == null) { stopSelf(startId); return START_NOT_STICKY }
        scope.launch {
            runCatching { Agent.run(prompt) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
