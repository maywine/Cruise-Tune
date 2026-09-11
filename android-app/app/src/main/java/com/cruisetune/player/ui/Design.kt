package com.cruisetune.player.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import com.cruisetune.player.R
import android.content.res.ColorStateList
import kotlin.math.min

object Design {
    var background = Color.rgb(16, 20, 24); private set
    var panel = Color.rgb(27, 33, 39); private set
    var raised = Color.rgb(40, 47, 53); private set
    var text = Color.rgb(245, 243, 238); private set
    var secondary = Color.rgb(179, 187, 190); private set
    var accent = Color.rgb(221, 187, 132); private set
    var danger = Color.rgb(255, 139, 128); private set
    var light = false; private set
    var highContrast = false; private set
    var reduceTransparency = false; private set
    fun configure(light: Boolean, contrast: Boolean, solid: Boolean) {
        this.light = light
        highContrast = contrast; reduceTransparency = solid
        background = Color.parseColor(if (light) "#F2F0EB" else "#101418")
        panel = Color.parseColor(if (light) "#FFFFFF" else "#1B2127")
        raised = Color.parseColor(if (light) "#E2E2DD" else "#282F35")
        text = Color.parseColor(if (light) "#161B20" else "#F5F3EE")
        secondary = if (contrast) text else Color.parseColor(if (light) "#565F65" else "#B3BBBE")
        accent = Color.parseColor(if (light) "#815815" else "#DDBB84")
        danger = Color.parseColor(if (light) "#B42318" else "#FF8B80")
    }
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun surface(color: Int, radius: Float = 24f, stroke: Int? = null) = GradientDrawable().apply {
        setColor(if (reduceTransparency || highContrast) color else (color and 0x00ffffff) or (248 shl 24)); cornerRadius = radius
        if (stroke != null || highContrast) setStroke(1, stroke ?: secondary)
    }
    fun reducedMotion(context: Context): Boolean {
        return context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("reduceMotion", false) || Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    fun applySystemBars(window: Window) {
        window.statusBarColor = background
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= 26) background else Color.rgb(16, 20, 24)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light && Build.VERSION.SDK_INT >= 26
        }
    }
    fun styleDialog(dialog: AlertDialog, context: Context, destructive: Boolean = false) {
        dialog.window?.apply {
            setBackgroundDrawable(surface(panel, context.dp(28).toFloat()))
            setWindowAnimations(0)
            applySystemBars(this)
            setLayout(minOf(context.dp(640),context.resources.displayMetrics.widthPixels-context.dp(32)),android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize = 20f; setTextColor(Design.text); setLineSpacing(context.dp(5).toFloat(),1f)
        }
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply { textSize = 24f; setTextColor(Design.text) }
        for (which in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                minHeight = context.dp(76); textSize = 21f
                setTextColor(if(destructive && which == AlertDialog.BUTTON_POSITIVE) danger else if(which == AlertDialog.BUTTON_NEGATIVE) Design.text else accent)
            }
        }
    }
    fun label(context: Context, value: String, size: Float = 22f, color: Int = text, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
}

/** High-frequency touch controls use immediate state feedback, without scale animations. */
class TouchButton(context: Context, label: String, private val emphasized: Boolean = false, private val destructive: Boolean = false) : androidx.appcompat.widget.AppCompatTextView(context) {
    private var selection: Boolean? = null
    private var appearanceReady = false
    init {
        text = label; textSize = 22f; includeFontPadding = false
        gravity = android.view.Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isClickable = true; isFocusable = true; maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        val density = resources.displayMetrics.density
        minHeight = (76 * density).toInt(); minWidth = (76 * density).toInt()
        setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        appearanceReady = true; refreshAppearance()
    }
    private fun refreshAppearance() {
        if (!appearanceReady) return
        val selected = selection == true || emphasized
        setTextColor(if (destructive) Design.danger else if (selected) Design.background else Design.text)
        background = Design.surface(if (selected) Design.accent else Design.raised, 22 * resources.displayMetrics.density,
            if (hasFocus()) Design.accent else null)
        alpha = if (!isEnabled) .42f else if (isPressed) .78f else 1f
    }
    fun selectedState(selected: Boolean) {
        if (selection == selected) return
        selection = selected; isSelected = selected
        ViewCompat.setStateDescription(this, if (selected) "已选中" else "未选中")
        refreshAppearance()
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (appearanceReady) alpha = if (!isEnabled) .42f else if (isPressed) .78f else 1f
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); refreshAppearance()
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.isCheckable = selection != null
        info.isChecked = selection == true
    }
    override fun performClick(): Boolean = super.performClick()
}

class CalmSwitch(context: Context) : androidx.appcompat.widget.SwitchCompat(context) {
    init {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Design.accent, Design.secondary))
        trackTintList = ColorStateList(states, intArrayOf(Design.raised, Design.raised))
    }
    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        if (Design.reducedMotion(context)) jumpDrawablesToCurrentState()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Design.reducedMotion(context)) jumpDrawablesToCurrentState()
    }
}

/** A centered control row whose children still receive the actual available width. */
class BoundedControlRow(context: Context, private val maximumDp: Int) : android.widget.LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = min(MeasureSpec.getSize(widthMeasureSpec), (maximumDp * resources.displayMetrics.density).toInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}

/** Quiet code-drawn cover fallback. No animated background or network placeholder artwork. */
class CoverView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private var gradient: Shader? = null
    private var bitmap: Bitmap? = null
    var seed: Int = 0; set(value) { field = value; rebuildGradient(); invalidate() }
    init { contentDescription = "当前歌曲封面" }
    fun artwork(value: Bitmap?) { bitmap = value; invalidate() }
    private fun rebuildGradient() {
        if (width <= 0 || height <= 0) return
        val hue = ((seed.toLong().and(0xffffffffL) % 50) + 18).toFloat()
        gradient = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.HSVToColor(floatArrayOf(hue, .40f, .82f)), Color.rgb(50, 59, 67), Shader.TileMode.CLAMP)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = min(w, h) * .1f
        clip.reset(); rect.set(0f, 0f, w.toFloat(), h.toFloat()); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        rebuildGradient()
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.save(); canvas.clipPath(clip)
        bitmap?.let {
            val scale = maxOf(w / it.width, h / it.height)
            val bw = it.width * scale; val bh = it.height * scale
            paint.shader = null
            rect.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
            canvas.drawBitmap(it, null, rect, paint)
        } ?: run {
            paint.shader = gradient
            canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
            paint.color = Color.argb(80, 20, 25, 29)
            canvas.drawCircle(w * .62f, h * .52f, w * .42f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = w * .008f
            paint.color = Color.argb(90, 244, 223, 189)
            for (i in 0..5) canvas.drawCircle(w * .62f, h * .52f, w * (.13f + i * .047f), paint)
            paint.style = Paint.Style.FILL; paint.color = Design.accent
            canvas.drawCircle(w * .62f, h * .52f, w * .052f, paint)
            paint.color = Color.argb(200, 244, 237, 220)
            rect.set(w * .13f, h * .18f, w * .17f, h * .46f); canvas.drawRoundRect(rect, w * .02f, w * .02f, paint)
            rect.set(w * .21f, h * .12f, w * .25f, h * .54f); canvas.drawRoundRect(rect, w * .02f, w * .02f, paint)
        }
        canvas.restore()
    }
}
