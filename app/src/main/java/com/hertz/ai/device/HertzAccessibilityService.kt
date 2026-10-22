package com.hertz.ai.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.view.Display
import com.hertz.ai.agent.Automations
import java.io.ByteArrayOutputStream
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hertz's hands and eyes. Android lets an accessibility service read what's on screen,
 * press buttons, type, swipe, and draw an overlay on top of other apps.
 */
class HertzAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: HertzAccessibilityService? = null
            private set

        private const val MAX_ELEMENTS = 140
        private const val MAX_CHARS = 5000
    }

    private val main = Handler(Looper.getMainLooper())
    private val elements = HashMap<Int, AccessibilityNodeInfo>()
    private var overlay: Overlay? = null

    /** Runs "when charging" scheduled tasks. */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Automations.onCharging()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = Overlay(this).also { it.show() }
        androidx.core.content.ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(powerReceiver) }
        overlay?.destroy()
        overlay = null
        super.onDestroy()
    }

    fun onMain(block: () -> Unit) = main.post(block)

    fun setOverlayVisible(visible: Boolean) = onMain { overlay?.setVisible(visible) }

    // ---------- Reading the screen ----------

    /** The window the user is looking at, skipping Hertz's own floating overlay. */
    private fun currentRoot(): AccessibilityNodeInfo? {
        val candidates = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val pick = candidates.firstOrNull { it.isActive }
            ?: candidates.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return pick?.root ?: rootInActiveWindow
    }

    fun readScreen(): String {
        val root = currentRoot() ?: return "I can't see the screen right now (it may be locked or loading)."
        val lines = ArrayList<String>()
        synchronized(elements) {
            elements.clear()
            var count = 0
            var chars = 0

            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (count >= MAX_ELEMENTS || chars >= MAX_CHARS || depth > 40) return
                if (!node.isVisibleToUser) return
                val own = labelOf(node)
                val clickable = node.isClickable || node.isLongClickable
                val interesting = own.isNotEmpty() || node.isEditable || node.isScrollable ||
                    node.isCheckable || clickable
                if (interesting) {
                    val label = own.ifEmpty { if (clickable) childLabel(node) else "" }
                    if (label.isNotEmpty() || node.isEditable || node.isScrollable || clickable) {
                        count++
                        elements[count] = node
                        val line = describe(count, node, label)
                        lines += line
                        chars += line.length
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    visit(child, depth + 1)
                }
            }
            visit(root, 0)
        }
        val app = root.packageName?.toString() ?: "unknown"
        val header = "Screen of app $app (${lines.size} elements). Use the [number] with tap / type_text."
        return if (lines.isEmpty()) "$header\n(no readable elements; try waiting, scrolling, or tap_at)"
        else header + "\n" + lines.joinToString("\n")
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val parts = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            if (node.isEditable) node.hintText?.toString()?.let { "hint: $it" } else null,
        ).map { it.trim().replace('\n', ' ') }.filter { it.isNotEmpty() }.distinct()
        return parts.joinToString(" / ").take(90)
    }

    private fun childLabel(node: AccessibilityNodeInfo): String {
        val found = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (found.size >= 2 || d > 4) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val l = labelOf(c)
                if (l.isNotEmpty()) found += l
                walk(c, d + 1)
            }
        }
        walk(node, 0)
        return found.joinToString(" · ").take(90)
    }

    private fun describe(id: Int, node: AccessibilityNodeInfo, label: String): String {
        val kind = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val flags = buildList {
            if (node.isClickable) add("tap")
            if (node.isEditable) add("editable")
            if (node.isScrollable) add("scrolls")
            if (node.isCheckable) add(if (node.isChecked) "ON" else "OFF")
            if (node.isSelected) add("selected")
            if (node.isFocused) add("focused")
            if (!node.isEnabled) add("disabled")
        }
        val b = Rect().also { node.getBoundsInScreen(it) }
        val flagText = if (flags.isEmpty()) "" else " (${flags.joinToString(",")})"
        return "[$id] $kind \"$label\"$flagText @${b.centerX()},${b.centerY()}"
    }

    private fun element(id: Int): AccessibilityNodeInfo? = synchronized(elements) { elements[id] }

    // ---------- Seeing the screen ----------

    /** Takes a screenshot and returns it as a JPEG, scaled down so the model can look at it quickly. */
    fun screenshotJpeg(maxSide: Int = 896): ByteArray? {
        val latch = CountDownLatch(1)
        var result: ByteArray? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    shot.hardwareBuffer.close()
                    if (bmp != null) {
                        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                        val small = if (scale < 1f)
                            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                        else bmp
                        val out = ByteArrayOutputStream()
                        small.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        result = out.toByteArray()
                        if (small !== bmp) small.recycle()
                        bmp.recycle()
                    }
                    hw?.recycle()
                } catch (t: Throwable) {
                    // Runs on the main thread: an error here (e.g. out of memory) would crash the app.
                    result = null
                } finally {
                    latch.countDown()
                }
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    // ---------- Acting on the screen ----------

    fun tap(id: Int, long: Boolean = false): String {
        val node = element(id) ?: return "No element [$id]. Call read_screen first."
        node.refresh()
        val label = labelOf(node).ifEmpty { childLabel(node) }.ifEmpty { "element $id" }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !(if (long) target.isLongClickable else target.isClickable)) {
            target = target.parent
        }
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (target?.performAction(action) == true) return "${if (long) "Long-pressed" else "Tapped"} “$label”."
        val b = Rect().also { node.getBoundsInScreen(it) }
        return if (tapAt(b.centerX().toFloat(), b.centerY().toFloat(), if (long) 700 else 60))
            "${if (long) "Long-pressed" else "Tapped"} “$label” by touch."
        else "Couldn't tap “$label”."
    }

    fun typeText(id: Int?, text: String, pressEnter: Boolean): String {
        var node: AccessibilityNodeInfo? = id?.let { element(it) }
        if (node == null) node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) node = synchronized(elements) { elements.values.firstOrNull { it.isEditable } }
        if (node == null) return "No text field found. Tap a text box first, then call type_text."
        if (!node.isEditable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(400)
            node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: node
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "That field would not accept text."
        if (pressEnter) {
            Thread.sleep(150)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "Typed “${text.take(60)}”${if (pressEnter) " and pressed enter" else ""}."
    }

    fun tapAt(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(path, durationMs)
    }

    fun scroll(direction: String): String {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val ok = when (direction.lowercase().trim()) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f)
            "up" -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            "left" -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
            else -> return "Direction must be up, down, left or right."
        }
        return if (ok) "Scrolled $direction." else "Scroll failed."
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        main.post {
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { success = true; latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return success
    }

    fun pressKey(key: String): String {
        val action = when (key.lowercase().replace(" ", "_")) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps", "app_switcher" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock", "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "power", "power_menu" -> GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> return "Unknown key. Use back, home, recents, notifications, quick_settings, screenshot, lock, power, split_screen."
        }
        return if (performGlobalAction(action)) "Pressed $key." else "Couldn't press $key."
    }
}
