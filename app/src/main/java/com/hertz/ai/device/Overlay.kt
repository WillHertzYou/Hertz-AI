package com.hertz.ai.device

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hertz.ai.MainActivity
import com.hertz.ai.R
import com.hertz.ai.agent.Agent
import com.hertz.ai.agent.ChatStore
import com.hertz.ai.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating Hertz orb. Tap: open the command panel. Hold: talk. Drag: move it.
 * While Hertz works, the orb pulses and a caption shows what it is doing.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class Overlay(private val service: HertzAccessibilityService) {
    private val ctx: Context = service
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val cyan = Color.parseColor("#22D3EE")
    private val ink = Color.parseColor("#E6EDF5")
    private val muted = Color.parseColor("#8A97A8")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    // ----- Orb -----
    private val orb = FrameLayout(ctx).apply {
        background = ctx.getDrawable(R.drawable.orb_bg)
        elevation = dp(8).toFloat()
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_launcher_foreground) },
            FrameLayout.LayoutParams(dp(58), dp(58)))
    }
    private val orbParams = baseParams(dp(58), dp(58), focusable = false).apply {
        gravity = Gravity.TOP or Gravity.START
        x = ctx.resources.displayMetrics.widthPixels - dp(66)
        y = ctx.resources.displayMetrics.heightPixels / 3
    }
    private val pulse = ObjectAnimator.ofFloat(orb, View.ALPHA, 1f, 0.45f).apply {
        duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
    }

    // ----- Caption bubble -----
    private val caption = TextView(ctx).apply {
        background = ctx.getDrawable(R.drawable.chip_bg)
        setTextColor(ink); textSize = 13f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private val captionParams = baseParams(dp(250), WindowManager.LayoutParams.WRAP_CONTENT, focusable = false, touchable = false).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    // ----- Command panel -----
    private val reply = TextView(ctx).apply {
        setTextColor(ink); textSize = 14f; maxLines = 6; ellipsize = TextUtils.TruncateAt.END
        text = "Good day, sir. What shall I do?"
    }
    private val input = EditText(ctx).apply {
        hint = "Tell Hertz what to do…"
        setHintTextColor(muted); setTextColor(ink); textSize = 15f
        background = ctx.getDrawable(R.drawable.input_bg)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        imeOptions = EditorInfo.IME_ACTION_SEND
        isSingleLine = true
        setOnEditorActionListener { _, _, _ -> sendTyped(); true }
    }
    private val stopBtn = chip("■ Stop") { Agent.stop() }
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ctx.getDrawable(R.drawable.panel_bg)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        elevation = dp(12).toFloat()
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_launcher_foreground) },
                LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(TextView(ctx).apply {
                text = "HERTZ"; setTextColor(cyan); textSize = 13f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.2f
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            addView(chip("Open app") {
                collapse()
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            addView(chip("✕") { collapse() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        })
        addView(reply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(12) })
        addView(input, LinearLayout.LayoutParams(-1, -2))
        addView(LinearLayout(ctx).apply {
            addView(chip("🎙 Talk") { listen() })
            addView(chip("✨ Suggest") { collapse(); Agent.suggest() }, gap())
            addView(chip("Send ➤") { sendTyped() }, gap())
            addView(stopBtn, gap())
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }
    private val panelParams = baseParams(dp(340), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(90)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }

    private var orbShown = false
    /** Set once screen control is switched off; late callbacks (e.g. speech results) must not add windows. */
    private var destroyed = false
    /** False when the user switched the orb off in Setup; then no captions pop up either. */
    private var visible = true
    private var panelShown = false
    private var captionShown = false
    private val hideCaption = Runnable { removeCaption() }

    private fun gap() = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }

    private fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(ink); textSize = 13f
        background = ctx.getDrawable(R.drawable.chip_bg)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
    }

    private fun baseParams(w: Int, h: Int, focusable: Boolean, touchable: Boolean = true) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

    fun show() {
        attachOrbTouch()
        panel.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) collapse()
            false
        }
        if (!orbShown) { wm.addView(orb, orbParams); orbShown = true }
        setVisible(com.hertz.ai.Prefs.showOrb)

        Agent.onTaskStart = { service.onMain { collapse() } }
        scope.launch {
            Agent.busy.collect { busy ->
                if (busy) {
                    pulse.start(); caption.removeCallbacks(hideCaption)
                } else {
                    pulse.cancel(); orb.alpha = 1f
                    caption.removeCallbacks(hideCaption); caption.postDelayed(hideCaption, 7000)
                }
                stopBtn.visibility = if (busy) View.VISIBLE else View.GONE
            }
        }
        scope.launch {
            ChatStore.status.collect { s ->
                if (s.isBlank()) return@collect
                reply.text = s
                if (!panelShown) showCaption(s)
            }
        }
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        orb.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) { collapse(); removeCaption() }
    }

    fun destroy() {
        destroyed = true
        scope.cancel()
        Agent.onTaskStart = null
        runCatching { if (orbShown) wm.removeView(orb) }
        runCatching { if (panelShown) wm.removeView(panel) }
        runCatching { if (captionShown) wm.removeView(caption) }
    }

    private fun attachOrbTouch() {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longFired = false
        val longPress = Runnable { longFired = true; listen() }
        orb.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = orbParams.x; startY = orbParams.y
                    dragging = false; longFired = false
                    orb.postDelayed(longPress, longMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true; orb.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        orbParams.x = startX + dx.toInt(); orbParams.y = startY + dy.toInt()
                        wm.updateViewLayout(orb, orbParams)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    orb.removeCallbacks(longPress)
                    if (dragging) snapToEdge()
                    else if (!longFired && e.action == MotionEvent.ACTION_UP) togglePanel()
                }
            }
            true
        }
    }

    private fun snapToEdge() {
        val w = ctx.resources.displayMetrics.widthPixels
        orbParams.x = if (orbParams.x + dp(29) < w / 2) dp(8) else w - dp(66)
        wm.updateViewLayout(orb, orbParams)
    }

    private fun togglePanel() = if (panelShown) collapse() else expand()

    private fun expand() {
        if (panelShown || destroyed) return
        removeCaption()
        wm.addView(panel, panelParams)
        panelShown = true
        input.requestFocus()
    }

    fun collapse() {
        if (!panelShown) return
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        runCatching { wm.removeView(panel) }
        panelShown = false
    }

    private fun sendTyped() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        input.setText("")
        collapse()
        Agent.submit(cmd)
    }

    private fun listen() {
        collapse()
        showCaption("Listening…")
        Voice.listen(
            onPartial = { showCaption("“$it”") },
            onResult = { heard ->
                if (heard.isBlank()) showCaption(Voice.listenError.value ?: "I didn't catch that, sir.")
                else Agent.submit(heard)
            },
        )
    }

    private fun showCaption(s: String) {
        if (!visible || destroyed) return
        caption.text = s
        val w = ctx.resources.displayMetrics.widthPixels
        val onRight = orbParams.x > w / 2
        captionParams.x = if (onRight) orbParams.x - dp(258) else orbParams.x + dp(66)
        captionParams.y = orbParams.y + dp(6)
        if (captionShown) wm.updateViewLayout(caption, captionParams)
        else { wm.addView(caption, captionParams); captionShown = true }
        caption.removeCallbacks(hideCaption)
        if (!Agent.busy.value) caption.postDelayed(hideCaption, 7000)
    }

    private fun removeCaption() {
        if (captionShown) { runCatching { wm.removeView(caption) }; captionShown = false }
    }
}
