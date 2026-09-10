package com.cruisetune.player.ui

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.R
import com.cruisetune.player.core.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class UiReviewFixesTest {
    @Before fun isolateLayoutFromTheMediaService() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app,com.cruisetune.player.playback.PlaybackService::class.java))
    }
    private fun views(view:View):List<View> = listOf(view) + if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun measure(activity:MainActivity,width:Int,height:Int) {
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(activity.findViewById(R.id.player_root),
            androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(),androidx.core.graphics.Insets.of(0,24,0,0)).build())
        activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        activity.window.decorView.layout(0,0,width,height)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun coreControlsFitShortAndNarrowLandscapeAndLargeText() {
        for ((w,h,font) in listOf(Triple(640,360,1f),Triple(667,375,1f),Triple(853,480,1f),Triple(1280,720,1f),Triple(640,360,1.6f))) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-land-mdpi")
            RuntimeEnvironment.setFontScale(font)
            val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val a=controller.get();measure(a,w,h)
                val root=a.findViewById<View>(R.id.player_root);val rootRect=Rect();root.getGlobalVisibleRect(rootRect)
                for(id in listOf(R.id.player_seek,R.id.player_times,R.id.player_tabs,R.id.player_previous,R.id.player_play,R.id.player_next)) {
                    val v=a.findViewById<View>(id);val visible=Rect()
                    assertTrue("$w x $h, font $font: hidden $id",v.getGlobalVisibleRect(visible))
                    assertEquals("$w x $h: clipped width $id",v.width,visible.width())
                    assertEquals("$w x $h: clipped height $id",v.height,visible.height())
                }
                val timeRow=a.findViewById<ViewGroup>(R.id.player_times)
                for(i in 0 until timeRow.childCount) {
                    val text=timeRow.getChildAt(i) as TextView
                    assertTrue("Time glyphs must fit: $w x $h font $font",text.layout.height<=text.height-text.compoundPaddingTop-text.compoundPaddingBottom)
                }
                assertTrue("The list must retain a useful viewport",a.findViewById<View>(R.id.player_list).height>=64)
            } finally {controller.pause().stop().destroy()}
        }
    }
    @Test fun touchButtonHasCurrentTextRoleAndSelectionSemanticsWithoutScale() {
        val context=RuntimeEnvironment.getApplication()
        val button=TouchButton(context,"顺序")
        button.text="循环";button.selectedState(true)
        val info=AccessibilityNodeInfo.obtain();button.onInitializeAccessibilityNodeInfo(info)
        assertEquals("android.widget.Button",info.className);assertEquals("循环",info.text.toString());assertTrue(info.isChecked)
        button.isPressed=true;assertEquals(1f,button.scaleX);assertEquals(1f,button.scaleY);assertTrue(button.alpha<1f)
        button.isPressed=false;assertEquals(1f,button.alpha)
        button.isEnabled=false;assertTrue(button.alpha<.5f)
    }
    @Test fun currentRowDistinguishesPausedBufferingAndPlaying() {
        val context=RuntimeEnvironment.getApplication()
        val adapter=TrackAdapter(false){}
        val track=Track("song","source","file","Song",relativePath="song.flac")
        adapter.submitList(listOf(track));shadowOf(Looper.getMainLooper()).idle()
        val holder=adapter.onCreateViewHolder(android.widget.FrameLayout(context),0)
        for(label in listOf("已暂停","正在缓冲","正在播放")) {
            adapter.updatePlayback("song",label);adapter.onBindViewHolder(holder,0)
            assertTrue(holder.row.contentDescription.toString().contains(label))
            if(label!="正在播放")assertFalse(holder.row.contentDescription.toString().contains("正在播放"))
            assertTrue(holder.row.isSelected)
        }
    }
    @Test fun appearanceChangesKeepTheSameSettingsPanelOpen() {
        RuntimeEnvironment.setQualifiers("w853dp-h480dp-land-mdpi")
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=controller.get();measure(a,853,480)
            views(a.window.decorView).filterIsInstance<TouchButton>().first{it.text.toString()=="设置"}.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.window!!.decorView.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY))
            dialog.window!!.decorView.layout(0,0,640,420)
            val scroll=dialog.findViewById<android.widget.ScrollView>(R.id.settings_scroll)!!
            scroll.scrollTo(0,120);val originalScroll=scroll.scrollY
            val day=views(dialog.window!!.decorView).filterIsInstance<CalmSwitch>().first{it.text.toString()=="日间模式"}
            day.isChecked=true;shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing);assertSame(dialog,ShadowDialog.getLatestDialog());assertTrue(Design.light)
            assertEquals(originalScroll,scroll.scrollY)
            assertTrue(androidx.core.view.WindowCompat.getInsetsController(a.window,a.window.decorView).isAppearanceLightStatusBars)
            val contrast=views(dialog.window!!.decorView).filterIsInstance<CalmSwitch>().first{it.text.toString()=="提高对比度"}
            contrast.isChecked=true;shadowOf(Looper.getMainLooper()).idle();assertTrue(dialog.isShowing);assertSame(dialog,ShadowDialog.getLatestDialog())
            assertEquals(0,dialog.window!!.attributes.windowAnimations)
            val reduced=views(dialog.window!!.decorView).filterIsInstance<CalmSwitch>().first{it.text.toString()=="减少动态效果"}
            reduced.isChecked=true;shadowOf(Looper.getMainLooper()).idle();assertEquals(0,dialog.window!!.attributes.windowAnimations)
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun existingMusicDirectoriesPrecedeAccountAndLoginActions() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=controller.get()
            ReflectionHelpers.setField(a,"library",com.cruisetune.player.data.LibraryState(sources=listOf(com.cruisetune.player.core.MusicSource("source",com.cruisetune.player.core.SourceKind.LOCAL,"我的收藏","content://music"))))
            views(a.window.decorView).filterIsInstance<TouchButton>().first{it.text.toString()=="来源"}.performClick()
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            val labels=views(dialog.window!!.decorView).filterIsInstance<TouchButton>().map{it.text.toString()}
            assertTrue(labels.indexOf("我的收藏")<labels.indexOf("添加音乐目录"))
            assertTrue(labels.indexOf("我的收藏")<labels.indexOf("账号管理"))
            assertFalse(labels.contains("重新网页登录"))
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun unknownDurationIsNotDisplayedAsZero() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val timeRow=controller.get().findViewById<ViewGroup>(R.id.player_times)
            assertEquals("—",(timeRow.getChildAt(1) as TextView).text.toString())
        } finally {controller.pause().stop().destroy()}
    }
}
