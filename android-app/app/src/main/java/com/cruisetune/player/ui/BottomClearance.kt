package com.cruisetune.player.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cruisetune.player.R
import com.cruisetune.player.ui.Design.dp

/** User-selected minimum bottom safe area; these presets are not measured OEM Dock heights. */
internal object BottomClearance {
    const val KEY = "bottomClearanceDp"
    val presets = listOf(0 to "自动", 100 to "小", 112 to "标准", 124 to "大", 136 to "特大")
    fun selected(context: Context): Int = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        .getInt(KEY, 0).takeIf { value -> presets.any { it.first == value } } ?: 0
    fun label(context: Context): String = presets.first { it.first == selected(context) }.second
    fun select(context: Context, value: Int) {
        require(presets.any { it.first == value })
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putInt(KEY, value).apply()
    }
    fun bottom(context: Context, systemBottom: Int): Int = maxOf(systemBottom, context.dp(selected(context)))
    fun contentHeightDp(context: Context): Int = (context.resources.configuration.screenHeightDp - selected(context)).coerceAtLeast(1)
    private fun reservation(context: Context) = (context as? Activity)?.window?.decorView
        ?.getTag(R.id.bottom_clearance_window) as? WindowReservation

    fun applyTo(root: View) {
        val base = Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        val window = reservation(root.context)
        var safe = Insets.NONE
        var insetCrop = window?.croppedPixels ?: 0
        fun apply() {
            val cropped = window?.croppedPixels ?: 0
            // Insets refer to the window size at dispatch; a resize must not reserve the same
            // pixels in both the surface bounds and the content padding.
            val systemBottom = (safe.bottom + insetCrop - cropped).coerceAtLeast(0)
            val manualBottom = (root.context.dp(selected(root.context)) - cropped).coerceAtLeast(0)
            root.setPadding(base.left + safe.left, base.top + safe.top, base.right + safe.right,
                base.bottom + maxOf(systemBottom, manualBottom))
        }
        window?.updateContent = { apply(); ViewCompat.requestApplyInsets(root) }
        apply()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetCrop = window?.croppedPixels ?: 0
            window?.onInsets(safe.bottom, safe.top)
            apply()
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Bound the entire dialog, including its completion button, above the reserved area. */
    fun fitDialog(dialog: AlertDialog, context: Context) {
        val activity = context as? Activity ?: return
        val window = dialog.window ?: return
        val content = window.findViewById<ViewGroup>(android.R.id.content) ?: return
        val reserve = context.dp(selected(context))
        var bounded = (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<BoundedDialog>().firstOrNull()
        if (reserve == 0 && bounded == null) return
        if (bounded == null) {
            bounded = BoundedDialog(context, window.attributes.flags, window.attributes.gravity)
            while (content.childCount > 0) {
                val child = content.getChildAt(0); content.removeView(child); bounded.panel.addView(child)
            }
            content.addView(bounded, ViewGroup.LayoutParams(-1, -2))
            bounded.setOnClickListener {
                // Older Android closes on DOWN outside the slop, newer versions on UP.
                // Let Dialog enforce its own cancelable / close-on-outside rules in both cases.
                val outside = -ViewConfiguration.get(context).scaledWindowTouchSlop.toFloat() - 1
                val time = android.os.SystemClock.uptimeMillis()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time, action, outside, outside, 0)
                    try { dialog.onTouchEvent(event) } finally { event.recycle() }
                }
            }
        }
        val safe = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val reservation = reservation(context)
        val height = reservation?.fullHeight?.takeIf { it > 0 }
            ?: activity.window.decorView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val systemBottom = reservation?.fullSystemBottom ?: safe.bottom
        val systemTop = maxOf(reservation?.fullSystemTop ?: 0, safe.top)
        val viewport = (height - systemTop - maxOf(systemBottom, reserve)).coerceAtLeast(1)
        bounded.reserved = reserve != 0
        bounded.setBackgroundColor(if (reserve == 0) Color.TRANSPARENT else Color.argb((window.attributes.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
        bounded.panel.background = if (reserve == 0) null else Design.surface(Design.panel, context.dp(28).toFloat())
        if (reserve != 0) window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.attributes = window.attributes.apply {
            y = 0
            if (reserve != 0) { width = ViewGroup.LayoutParams.MATCH_PARENT; this.height = viewport }
            gravity = if (reserve == 0) bounded.originalGravity else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val mask = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            // The backdrop belongs to this bounded window, so it dims/catches taps inside
            // the app while leaving only the reserved Dock area outside its touch region.
            flags = (flags and mask.inv()) or if (reserve == 0) bounded.originalFlags and mask
                else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        bounded.requestLayout()
    }

    /** Crop the application surface itself; padding alone still paints over a lower OEM window. */
    class WindowReservation(private val activity: Activity) : AutoCloseable {
        private val window = activity.window
        private val decor = window.decorView
        private val preferences = activity.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        private val original = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        private var dimensions = dimensions(activity.resources.configuration)
        var fullHeight = 0; private set
        var fullSystemBottom = 0; private set
        var fullSystemTop = 0; private set
        var croppedPixels = 0; private set
        var updateContent: (() -> Unit)? = null
        private val layout = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (fullHeight == 0 && view.height > 0) { fullHeight = view.height; update() }
        }
        private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY) update() }

        init {
            decor.setTag(R.id.bottom_clearance_window, this)
            decor.addOnLayoutChangeListener(layout)
            preferences.registerOnSharedPreferenceChangeListener(changes)
        }

        fun onInsets(bottom: Int, top: Int = fullSystemTop) {
            val previous = fullSystemBottom
            // Insets after cropping no longer describe the original window. Reusing them to
            // enlarge the crop can repeatedly subtract the same bar on OEM implementations.
            if (croppedPixels == 0) fullSystemBottom = bottom
            fullSystemTop = if (croppedPixels == 0) top else maxOf(fullSystemTop, top)
            if (previous != fullSystemBottom) update()
        }

        private fun update() {
            if (fullHeight == 0) return
            val reserve = activity.dp(selected(activity))
            val target = if (reserve == 0) 0 else maxOf(reserve, fullSystemBottom).coerceAtMost(fullHeight - 1)
            if (target == croppedPixels) return
            croppedPixels = target
            window.attributes = window.attributes.apply {
                height = if (target == 0) original.height else fullHeight - target
                gravity = if (target == 0) original.gravity else Gravity.TOP or Gravity.START
                // Let the real Dock receive taps in the space outside this window.
                flags = if (target == 0) (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()) or
                    (original.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            }
            updateContent?.invoke()
            ViewCompat.requestApplyInsets(decor)
        }

        fun configurationChanged(configuration: Configuration) {
            val next = dimensions(configuration)
            if (next == dimensions) return
            dimensions = next
            fullHeight = 0
            fullSystemBottom = 0
            fullSystemTop = 0
            croppedPixels = 0
            window.attributes = window.attributes.apply {
                height = original.height; gravity = original.gravity
                flags = (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()) or
                    (original.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            }
            updateContent?.invoke()
        }

        private fun dimensions(configuration: Configuration) = Triple(configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation)
        override fun close() {
            preferences.unregisterOnSharedPreferenceChangeListener(changes)
            decor.removeOnLayoutChangeListener(layout)
            decor.setTag(R.id.bottom_clearance_window, null)
            updateContent = null
        }
    }
    private class BoundedDialog(context: Context, val originalFlags: Int, val originalGravity: Int) : FrameLayout(context) {
        val panel = FrameLayout(context).apply { isClickable = true }
        var reserved = false
        init { addView(panel, LayoutParams(-1, -2, Gravity.CENTER)) }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!reserved) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            panel.measure(MeasureSpec.makeMeasureSpec(minOf(context.dp(640), (width - context.dp(32)).coerceAtLeast(1)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((height - context.dp(32)).coerceAtLeast(1), MeasureSpec.AT_MOST))
            setMeasuredDimension(width, height)
        }
    }
}
