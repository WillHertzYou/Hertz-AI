package com.hertz.ai.device

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets Hertz read your notifications and reply to messages straight from them. */
class HertzNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: HertzNotificationListener? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, HertzNotificationListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }

    data class Item(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val replyAction: Notification.Action?,
    )

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }
    override fun onDestroy() { instance = null; super.onDestroy() }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun recent(filter: String = ""): List<Item> {
        val all = runCatching { activeNotifications }.getOrNull().orEmpty()
        return all.asSequence()
            .filter { !it.isOngoing && it.packageName != packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { toItem(it) }
            .filter { it.title.isNotBlank() || it.text.isNotBlank() }
            .filter { filter.isBlank() || it.app.contains(filter, true) || it.title.contains(filter, true) }
            .sortedByDescending { it.postTime }
            .take(20)
            .toList()
    }

    private fun toItem(sbn: StatusBarNotification): Item {
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = big ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val reply = sbn.notification.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        return Item(sbn.key, appName(sbn.packageName), title, text.take(300), sbn.postTime, reply)
    }

    fun reply(item: Item, message: String): Boolean {
        val action = item.replyAction ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, message) } }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent); true }.getOrDefault(false)
    }

    fun dismiss(item: Item) = runCatching { cancelNotification(item.key) }.isSuccess
}
