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
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun emptyLibraryAddMusicLabelFitsAndOpensSources() {
        for((w,h,font) in listOf(Triple(640,360,1f),Triple(640,360,1.6f),Triple(360,640,1.6f))) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h)"land" else "port"}-mdpi")
            RuntimeEnvironment.setFontScale(font)
            val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val a=activity.get();measure(a,w,h+24)
                val button=a.findViewById<TextView>(R.id.player_play)
                assertEquals("添加音乐",button.text.toString())
                assertEquals("添加音乐目录",button.contentDescription.toString())
                assertTrue("Add music label must fit: $w x $h font $font, text=${button.textSize}, lines=${button.layout.lineCount}, textHeight=${button.layout.height}, button=${button.width}x${button.height}",button.layout.height<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom)
                assertEquals("The full add music label must be visible",button.text.length,button.layout.getLineEnd(button.layout.lineCount-1))
                for(line in 0 until button.layout.lineCount) assertEquals(0,button.layout.getEllipsisCount(line))
                button.performClick()
                val dialog=ShadowDialog.getLatestDialog()
                val labels=views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.contains("音乐来源"))
                assertTrue(labels.contains("添加音乐目录"))
                dialog.dismiss()
                ReflectionHelpers.callInstanceMethod<Unit>(a,"updatePlaybackAction",ReflectionHelpers.ClassParameter.from(PlaybackAction::class.java,PlaybackAction.PLAY))
                assertEquals("播放",button.text.toString())
                assertEquals(24f*button.resources.displayMetrics.scaledDensity,button.textSize,.01f)
                ReflectionHelpers.callInstanceMethod<Unit>(a,"updatePlaybackAction",ReflectionHelpers.ClassParameter.from(PlaybackAction::class.java,PlaybackAction.ADD))
                measure(a,w,h+24)
                assertEquals("添加音乐",button.text.toString())
                assertTrue(button.layout.height<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom)
            } finally {activity.pause().stop().destroy()}
        }
    }
    @Test fun defaultCloudEntryUsesWebQrEvenWhenATokenAccountExists() {
        val app=RuntimeEnvironment.getApplication() as CruiseApplication
        app.preferences.edit().remove("quarkAccount").putString("quarkDirectAccount","test-token-account").commit()
        val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(),"showAddSource")
            val dialog=ShadowDialog.getLatestDialog()
            views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString()=="连接夸克网盘" }.performClick()
            assertEquals(QuarkLoginActivity::class.java.name,shadowOf(activity.get()).nextStartedActivityForResult.intent.component!!.className)
            assertEquals("test-token-account",app.preferences.getString("quarkDirectAccount",null))
        } finally {activity.pause().stop().destroy();app.preferences.edit().remove("quarkDirectAccount").commit()}
    }
    @Test fun tokenAuthorizationRemainsAnExplicitAlternativeInAccountManagement() {
        val app=RuntimeEnvironment.getApplication() as CruiseApplication
        app.preferences.edit().remove("quarkAccount").remove("quarkDirectAccount").commit()
        val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(),"showAccountManagement")
            val dialog=ShadowDialog.getLatestDialog()
            val labels=views(dialog.window!!.decorView).filterIsInstance<TextView>()
            assertTrue(labels.any { it.text.toString()=="扫码登录夸克" })
            labels.single { it.text.toString()=="使用 Token 授权" }.performClick()
            assertEquals(QuarkDirectLoginActivity::class.java.name,shadowOf(activity.get()).nextStartedActivityForResult.intent.component!!.className)
        } finally {activity.pause().stop().destroy()}
    }
    private fun measure(activity:MainActivity,width:Int,height:Int) {
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(activity.findViewById(R.id.player_root),
            androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(),androidx.core.graphics.Insets.of(0,24,0,0)).build())
        activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        activity.window.decorView.layout(0,0,width,height)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun coreControlsFitShortAndNarrowLandscapeAndLargeText() {
        for ((w,h,font) in listOf(Triple(640,360,1f),Triple(667,375,1f),Triple(853,480,1f),Triple(1280,720,1f),Triple(640,360,1.6f),Triple(360,640,1f),Triple(360,640,1.6f))) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h) "land" else "port"}-mdpi")
            RuntimeEnvironment.setFontScale(font)
            val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val a=controller.get();measure(a,w,h)
                val root=a.findViewById<View>(R.id.player_root);val rootRect=Rect();root.getGlobalVisibleRect(rootRect)
                for(id in listOf(R.id.player_status,R.id.player_seek,R.id.player_times,R.id.player_tabs,R.id.player_sort,R.id.player_previous,R.id.player_play,R.id.player_next)) {
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
                if(w>h) {
                    val now=Rect();val footer=Rect();val tabs=Rect()
                    assertTrue(a.findViewById<View>(R.id.player_now).getGlobalVisibleRect(now))
                    assertTrue(a.findViewById<View>(R.id.player_footer).getGlobalVisibleRect(footer))
                    assertTrue(a.findViewById<View>(R.id.player_tabs).getGlobalVisibleRect(tabs))
                    assertEquals("$w x $h: playback controls must align with the player panel",now.left,footer.left)
                    assertEquals("$w x $h: playback controls must align with the player panel",now.right,footer.right)
                    assertTrue("$w x $h: playback controls must stay outside the queue",footer.right<tabs.left)
                }
            } finally {controller.pause().stop().destroy()}
        }
    }
    @Test fun landscapeFooterKeepsPrimaryActionsAndOrderToggleLeavesSettings() {
        RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land-mdpi")
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=controller.get();measure(a,1280,720)
            val footer=a.findViewById<ViewGroup>(R.id.player_footer)
            val controls=views(footer).filterIsInstance<TouchButton>()
            assertEquals(listOf(R.id.player_previous,R.id.player_play,R.id.player_next),controls.map { it.id })
            assertFalse(controls.any { it.text.toString() in listOf("随机","顺序","单曲","循环") })
            val order=a.findViewById<TouchButton>(R.id.player_order_toggle)
            assertNotNull(order)
            val secondary=order.parent as ViewGroup
            assertEquals(listOf(R.id.player_lyrics_toggle,R.id.player_order_toggle,R.id.player_offline),
                (0 until secondary.childCount).map { secondary.getChildAt(it).id })

            views(a.window.decorView).filterIsInstance<TouchButton>().single { it.text.toString()=="设置" }.performClick()
            val settings=ShadowDialog.getLatestDialog()
            val actions=views(settings.window!!.decorView).filterIsInstance<TouchButton>().map { it.text.toString() }
            assertFalse(actions.any { it.endsWith("随机播放") })
            assertFalse(actions.any { it.startsWith("播放顺序：") || it.contains("列表循环") || it.contains("单曲循环") })
        } finally {controller.pause().stop().destroy()}
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun inlineDetailsFitBetweenProgressAndMainControls() {
        for((w,h,font) in listOf(Triple(853,480,1f),Triple(960,540,1f),Triple(1280,720,1.6f))) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-land-mdpi");RuntimeEnvironment.setFontScale(font)
            val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val a=activity.get()
                val details=a.findViewById<TrackDetailsView>(R.id.player_details)
                assertNotNull(details)
                details.update("Artist","Album",true,LyricsState("song",com.cruisetune.player.core.LrcParser.parse("[00:00]A long synthetic current line that must not push the next cue outside the available space\n[00:04]A long synthetic next line that must remain visible with large fonts")),
                    0,true,com.cruisetune.player.core.OfflineState.AVAILABLE,"在线")
                // Configuration height excludes the status bar; the edge-to-edge decor includes it.
                measure(a,w,h+24)
                for(id in listOf(R.id.player_artist,R.id.player_album,R.id.player_lyric_current,R.id.player_lyric_next,R.id.player_offline,R.id.player_lyrics_toggle,R.id.player_order_toggle)) {
                    val view=a.findViewById<View>(id);val rect=Rect()
                    val now=a.findViewById<ViewGroup>(R.id.player_now)
                    val sizes=(0 until now.childCount).map { now.getChildAt(it).let { child -> "${child.javaClass.simpleName}:${child.height}" } }
                    assertTrue("$w x $h font $font: hidden $id, view=${view.width}x${view.height}, parent=${(view.parent as View).height}, details=${details.height}, now=${now.height}, children=$sizes",view.getGlobalVisibleRect(rect))
                    assertEquals("$w x $h font $font: clipped height $id",view.height,rect.height())
                }
            } finally {activity.pause().stop().destroy()}
        }
    }
    @Test fun compactStatusKeepsFullErrorAvailableOnTap() {
        RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-mdpi")
        RuntimeEnvironment.setFontScale(1.6f)
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=controller.get();val message="文件超过夸克当前的下载上限（50 MiB），请检查当前账号下载权限"
            a.findViewById<TextView>(R.id.player_status).text=message
            measure(a,640,360)
            val status=a.findViewById<TextView>(R.id.player_status);val rect=Rect()
            assertTrue(status.getGlobalVisibleRect(rect));assertEquals(status.height,rect.height())
            status.performClick()
            val dialog=ShadowDialog.getLatestDialog()
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()==message && it.ellipsize==null })
        } finally {controller.pause().stop().destroy()}
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun expandedDetailsKeepTitleProgressAndPlaybackControlsAvailable() {
        for((w,h,font) in listOf(Triple(640,360,1f),Triple(640,360,1.6f),Triple(360,640,1.6f))) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h)"land" else "port"}-mdpi")
            RuntimeEnvironment.setFontScale(font)
            val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val a=controller.get();measure(a,w,h+24)
                val before=Rect();a.findViewById<View>(R.id.player_footer).getGlobalVisibleRect(before)
                val dialogBefore=ShadowDialog.getLatestDialog()
                a.findViewById<View>(R.id.player_details_navigation).performClick()
                measure(a,w,h+24)
                assertSame(dialogBefore,ShadowDialog.getLatestDialog())
                val details=a.findViewById<TrackDetailsView>(R.id.player_details)
                details.update("Artist","Album",true,LyricsState("song",com.cruisetune.player.core.LrcParser.parse("[00:00]当前歌词\n[00:04]下一句歌词")),
                    0,true,com.cruisetune.player.core.OfflineState.AVAILABLE,"在线")
                measure(a,w,h+24)
                for(id in listOf(R.id.player_title,R.id.player_seek,R.id.player_times,R.id.player_previous,R.id.player_play,R.id.player_next,
                    R.id.player_lyric_current,R.id.player_lyric_next,R.id.player_offline,R.id.player_order_toggle,R.id.player_details_navigation)) {
                    val view=a.findViewById<View>(id);val rect=Rect()
                    assertTrue("$w x $h font $font: hidden $id",view.getGlobalVisibleRect(rect))
                    assertEquals("$w x $h font $font: clipped $id",view.height,rect.height())
                }
                val after=Rect();a.findViewById<View>(R.id.player_footer).getGlobalVisibleRect(after)
                assertEquals(before,after)
                val navigation=a.findViewById<TextView>(R.id.player_details_navigation)
                assertTrue("The return label must fit at font $font",navigation.layout.height<=navigation.height-navigation.compoundPaddingTop-navigation.compoundPaddingBottom)
                a.onBackPressedDispatcher.onBackPressed();measure(a,w,h+24)
                assertNotNull(a.findViewById<View>(R.id.player_list))
                assertEquals("详情",a.findViewById<TextView>(R.id.player_details_navigation).text.toString())
            } finally {controller.pause().stop().destroy()}
        }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun returningFromDetailsRestoresTheListPosition() {
        RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-mdpi");RuntimeEnvironment.setFontScale(1f)
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=controller.get();measure(a,360,664)
            ReflectionHelpers.setField(a,"library",com.cruisetune.player.data.LibraryState(tracks=(0 until 100).map {
                Track("song-$it","source","file-$it","Song $it")
            }))
            ReflectionHelpers.callInstanceMethod<Unit>(a,"renderList");measure(a,360,664)
            val list=a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.player_list)
            val manager=list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            manager.scrollToPositionWithOffset(35,0);measure(a,360,664)
            assertEquals(35,manager.findFirstVisibleItemPosition())
            a.findViewById<View>(R.id.player_details_navigation).performClick();measure(a,360,664)
            a.findViewById<View>(R.id.player_details_navigation).performClick();measure(a,360,664)
            val restored=a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.player_list).layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            assertEquals(35,restored.findFirstVisibleItemPosition())
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun removalDialogUsesReadableBodyAndDistinctDestructiveAction() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=activity.get();val dialog=AlertDialog.Builder(a).setTitle("移除音乐目录").setMessage("移除本机记录，网盘文件不会删除")
                .setNegativeButton("取消",null).setPositiveButton("移除",null).create()
            dialog.show();Design.styleDialog(dialog,a,destructive=true)
            val scale=a.resources.displayMetrics.scaledDensity
            assertEquals(20f,dialog.findViewById<TextView>(android.R.id.message)!!.textSize/scale,.01f)
            assertEquals(Design.danger,dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
            assertEquals(Design.text,dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor)
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).minHeight>=76*a.resources.displayMetrics.density)
            dialog.dismiss()
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun modePickerShowsCurrentModeAndSelectsAnExactTarget() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            var selected:Int?=null
            val dialog=PlaybackModes.show(activity.get(),androidx.media3.common.Player.REPEAT_MODE_ONE){selected=it}
            val radios=views(dialog.window!!.decorView).filterIsInstance<android.widget.RadioButton>()
            assertEquals(3,radios.size);assertEquals("单曲循环",radios.single { it.isChecked }.text.toString());assertNull(selected)
            radios.single { it.text.toString()=="列表循环" }.performClick()
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL,selected);assertFalse(dialog.isShowing)
        } finally {activity.pause().stop().destroy()}
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
            views(dialog.window!!.decorView).filterIsInstance<TouchButton>().single { it.text.toString()=="移除目录" }.performClick()
            val confirmation=ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(views(confirmation.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()=="移除 我的收藏" })
            assertTrue(confirmation.findViewById<TextView>(android.R.id.message)!!.text.toString().contains("原始音乐文件和其他目录不受影响"))
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals("source",ReflectionHelpers.getField<com.cruisetune.player.data.LibraryState>(a,"library").sources.single().id)
        } finally {controller.pause().stop().destroy()}
    }
    @Test fun librarySortIsVisibleAndSurvivesRecreation() {
        val app=RuntimeEnvironment.getApplication() as CruiseApplication
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=controller.get()
            a.findViewById<View>(R.id.player_sort).performClick()
            val dialog=ShadowDialog.getLatestDialog()
            views(dialog.window!!.decorView).filterIsInstance<android.widget.RadioButton>().single { it.text.toString()=="歌曲名称降序" }.performClick()
            assertEquals("TITLE_DESC",app.preferences.getString("trackSort",null))
            controller.recreate()
            controller.get().findViewById<View>(R.id.player_sort).performClick()
            val restored=ShadowDialog.getLatestDialog()
            val choice=views(restored.window!!.decorView).filterIsInstance<android.widget.RadioButton>().single { it.text.toString()=="歌曲名称降序" }
            assertTrue(choice.isChecked)
        } finally {controller.pause().stop().destroy();app.preferences.edit().remove("trackSort").commit()}
    }
    @Test fun unknownDurationIsNotDisplayedAsZero() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val timeRow=controller.get().findViewById<ViewGroup>(R.id.player_times)
            assertEquals("—",(timeRow.getChildAt(1) as TextView).text.toString())
        } finally {controller.pause().stop().destroy()}
    }
}
