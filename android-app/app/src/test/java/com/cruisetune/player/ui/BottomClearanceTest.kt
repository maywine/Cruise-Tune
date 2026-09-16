package com.cruisetune.player.ui

import android.content.ComponentName
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.R
import com.cruisetune.player.playback.PlaybackService
import com.cruisetune.player.ui.Design.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class BottomClearanceTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    @Before fun setup() {
        app.preferences.edit().remove(BottomClearance.KEY).commit()
        RuntimeEnvironment.setQualifiers("w960dp-h540dp-land-mdpi")
        RuntimeEnvironment.setFontScale(1f)
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }
    @After fun clean() { app.preferences.edit().remove(BottomClearance.KEY).commit() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun insets(top: Int, bottom: Int) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, top, 0, bottom)).build()
    private fun dispatchInsets(a: MainActivity, top: Int, bottom: Int) {
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            // On API 28 AndroidX uses the root's stable insets to distinguish navigation bars
            // from the keyboard. An attached root with stable bottom=0 would classify this
            // synthetic bottom inset as IME, unlike the navigation bar modeled by this test.
            val attach = ReflectionHelpers.getField<Any>(a.window.decorView, "mAttachInfo")
            ReflectionHelpers.getField<Rect>(attach, "mStableInsets").set(0, top, 0, bottom)
            ReflectionHelpers.getField<Rect>(attach, "mContentInsets").set(0, top, 0, bottom)
            val viewRoot = ReflectionHelpers.getField<Any>(attach, "mViewRootImpl")
            ReflectionHelpers.getField<Rect>(viewRoot, "mPendingStableInsets").set(0, top, 0, bottom)
            ReflectionHelpers.getField<Rect>(viewRoot, "mPendingContentInsets").set(0, top, 0, bottom)
            ReflectionHelpers.setField(viewRoot, "mLastWindowInsets", null)
        }
        ViewCompat.dispatchApplyWindowInsets(a.findViewById(R.id.player_root), insets(top, bottom))
    }
    private fun measure(a: MainActivity, w: Int, h: Int, top: Int, bottom: Int = 0) {
        // Model WindowManager applying the requested surface bounds, then dispatching only
        // the part of a system bar that still intersects that surface.
        repeat(2) {
            val height = a.window.attributes.height.takeIf { it > 0 } ?: h
            dispatchInsets(a, top, (bottom - (h - height)).coerceAtLeast(0))
            a.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            a.window.decorView.layout(0, 0, w, height)
        }
        shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's ViewRoot emits its default (zero-bottom) insets during layout.
        // Finish with the selected test display's actual bar/window intersection.
        val height = a.window.attributes.height.takeIf { it > 0 } ?: h
        dispatchInsets(a, top, (bottom - (h - height)).coerceAtLeast(0))
    }
    @Test fun manualMinimumDoesNotDoubleCountOrAccumulateSystemInsets() {
        val root = LinearLayout(app).apply { setPadding(16, 8, 16, 8) }
        BottomClearance.select(app, 112); BottomClearance.applyTo(root)
        repeat(3) { ViewCompat.dispatchApplyWindowInsets(root, insets(72, 80)) }
        assertEquals(120, root.paddingBottom); assertEquals(80, root.paddingTop)
        ViewCompat.dispatchApplyWindowInsets(root, insets(72, 180)); assertEquals(188, root.paddingBottom)
        BottomClearance.select(app, 0)
        ViewCompat.dispatchApplyWindowInsets(root, insets(72, 80)); assertEquals(88, root.paddingBottom)
    }
    @Test fun choosingAPresetPreviewsImmediatelyPersistsAndCanReturnToAutomatic() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a = owner.get(); measure(a, 960, 540, 24)
            ReflectionHelpers.callInstanceMethod<Unit>(a, "showBottomClearance")
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val originalDialogFlags = dialog.window!!.attributes.flags
            val choices = views(dialog.window!!.decorView).filterIsInstance<TouchButton>()
            assertEquals(listOf("自动", "小", "标准", "大", "特大"), choices.map { it.text.toString() })
            assertTrue(choices.all { it.minHeight >= a.dp(76) })
            choices.single { it.text == "标准" }.performClick(); measure(a, 960, 540, 24)
            assertTrue(dialog.isShowing); assertTrue(choices.single { it.text == "标准" }.isSelected)
            assertEquals(112, BottomClearance.selected(a)); assertEquals(8, a.findViewById<View>(R.id.player_root).paddingBottom)
            assertEquals(428, a.window.attributes.height)
            assertEquals(0, dialog.window!!.attributes.y)
            assertEquals(404, dialog.window!!.attributes.height)
            dialog.dismiss(); owner.recreate(); measure(owner.get(), 960, 540, 24)
            assertEquals(8, owner.get().findViewById<View>(R.id.player_root).paddingBottom)
            assertEquals(428, owner.get().window.attributes.height)
            ReflectionHelpers.callInstanceMethod<Unit>(owner.get(), "showBottomClearance")
            val again = ShadowDialog.getLatestDialog() as AlertDialog
            views(again.window!!.decorView).filterIsInstance<TouchButton>().single { it.text == "自动" }.performClick()
            measure(owner.get(), 960, 540, 24)
            assertEquals(8, owner.get().findViewById<View>(R.id.player_root).paddingBottom)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, owner.get().window.attributes.height)
            assertEquals(0, again.window!!.attributes.y); again.dismiss()
            val mask = WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            assertEquals(originalDialogFlags and mask, again.window!!.attributes.flags and mask)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun manualClearanceCropsTheWindowAndDoesNotAccumulateAcrossLayoutsOrPresets() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a = owner.get(); measure(a, 960, 540, 24)
            val original = a.window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            BottomClearance.select(a, 112); measure(a, 960, 540, 24)
            repeat(4) { measure(a, 960, 540, 24) }
            assertEquals(428, a.window.attributes.height)
            assertEquals(428, a.window.decorView.height)
            assertEquals(8, a.findViewById<View>(R.id.player_root).paddingBottom)
            assertNotEquals(0, a.window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            BottomClearance.select(a, 136); measure(a, 960, 540, 24)
            assertEquals(404, a.window.attributes.height)
            BottomClearance.select(a, 0); measure(a, 960, 540, 24)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, a.window.attributes.height)
            assertEquals(540, a.window.decorView.height)
            assertEquals(original, a.window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun reportedSystemBarAndWindowCropReserveTheSamePixelsOnlyOnce() {
        val owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a = owner.get(); measure(a, 960, 540, 24, 180)
            BottomClearance.select(a, 112); measure(a, 960, 540, 24, 180)
            assertEquals(360, a.window.attributes.height)
            assertEquals(8, a.findViewById<View>(R.id.player_root).paddingBottom)
            val reservation = a.window.decorView.getTag(R.id.bottom_clearance_window) as BottomClearance.WindowReservation
            repeat(4) { reservation.onInsets(180) }
            assertEquals("Repeated OEM insets must not keep shrinking the window", 360, a.window.attributes.height)
            BottomClearance.select(a, 0); measure(a, 960, 540, 24, 180)
            assertEquals(540, a.window.decorView.height)
            assertEquals(188, a.findViewById<View>(R.id.player_root).paddingBottom)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun aNewWindowSizeRebasesTheCropInsteadOfKeepingThePreviousHeight() {
        BottomClearance.select(app, 112)
        val owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a = owner.get(); measure(a, 960, 540, 24)
            val reservation = a.window.decorView.getTag(R.id.bottom_clearance_window) as BottomClearance.WindowReservation
            val configuration = android.content.res.Configuration(a.resources.configuration).apply {
                screenWidthDp = 540; screenHeightDp = 960; orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            reservation.configurationChanged(configuration)
            measure(a, 540, 960, 24)
            assertEquals(848, a.window.attributes.height)
            assertEquals(8, a.findViewById<View>(R.id.player_root).paddingBottom)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun everyPresetKeepsThePlayerAboveTheReservedDockIncludingLargeText() {
        for ((w, h, font) in listOf(Triple(1920,1080,1f), Triple(960,540,1f), Triple(853,480,1.6f), Triple(375,812,1.6f))) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h)"land" else "port"}-mdpi")
            RuntimeEnvironment.setFontScale(font)
            for ((reserve, _) in BottomClearance.presets) {
                BottomClearance.select(app, reserve)
                val owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
                try {
                    val a = owner.get(); measure(a,w,h,if(w==1920)72 else 24)
                    for (id in listOf(R.id.player_previous,R.id.player_play,R.id.player_next,R.id.player_seek,R.id.player_times)) {
                        val view = a.findViewById<View>(id); val rect = Rect()
                        assertTrue("$w/$h/$font/$reserve hidden $id",view.getGlobalVisibleRect(rect))
                        assertEquals("$w/$h/$font/$reserve clipped $id",view.height,rect.height())
                        assertTrue("$w/$h/$font/$reserve overlaps Dock $id",rect.bottom<=h-reserve)
                    }
                    assertTrue(a.findViewById<View>(R.id.player_list).height>=64)
                } finally { owner.pause().stop().destroy() }
            }
        }
    }
    @Test fun tallSettingsDialogReservesSpaceForCompletionButton() {
        BottomClearance.select(app,136)
        val owner=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=owner.get();measure(a,960,540,24)
            ReflectionHelpers.callInstanceMethod<Unit>(a,"showSettings",ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!,0))
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            val decor=dialog.window!!.decorView
            assertEquals(380, dialog.window!!.attributes.height)
            decor.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(380,View.MeasureSpec.EXACTLY))
            decor.layout(0,0,decor.measuredWidth,decor.measuredHeight)
            val content=dialog.findViewById<ViewGroup>(android.R.id.content)!!
            assertTrue((content.getChildAt(0) as ViewGroup).getChildAt(0).measuredHeight<=540-24-136-32)
            val done=dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val rect=Rect(0,0,done.width,done.height)
            val bounded=content.getChildAt(0) as ViewGroup
            bounded.offsetDescendantRectToMyCoords(done,rect)
            assertTrue("Done outside bounded panel: $rect / ${bounded.width}x${bounded.height}",
                rect.top>=0 && rect.bottom<=bounded.height && rect.left>=0 && rect.right<=bounded.width)
            assertTrue(done.height>=76);done.performClick();shadowOf(Looper.getMainLooper()).idle();assertFalse(dialog.isShowing)
            assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertNotEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun boundedBackdropHonorsTheDialogsOutsideCancellationPolicy() {
        BottomClearance.select(app, 112)
        val owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a = owner.get(); measure(a, 960, 540, 24)
            ReflectionHelpers.callInstanceMethod<Unit>(a, "showBottomClearance")
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val backdrop = dialog.findViewById<ViewGroup>(android.R.id.content)!!.getChildAt(0)
            dialog.setCanceledOnTouchOutside(false)
            backdrop.performClick()
            assertTrue(dialog.isShowing)
            dialog.setCanceledOnTouchOutside(true)
            backdrop.performClick()
            assertFalse(dialog.isShowing)
        } finally { owner.pause().stop().destroy() }
    }
}
