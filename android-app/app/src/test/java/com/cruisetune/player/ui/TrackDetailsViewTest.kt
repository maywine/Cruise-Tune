package com.cruisetune.player.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.cruisetune.player.R
import com.cruisetune.player.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class TrackDetailsViewTest {
    @Test fun orderToggleSitsBetweenLyricsAndOfflineAndReportsTheActualMode() {
        var changes=0
        val view=TrackDetailsView(RuntimeEnvironment.getApplication(),{}, {}, { changes++ })
        val order=view.findViewById<TouchButton>(R.id.player_order_toggle)
        val actions=order.parent as android.view.ViewGroup
        assertEquals(listOf(R.id.player_lyrics_toggle,R.id.player_order_toggle,R.id.player_offline),
            (0 until actions.childCount).map { actions.getChildAt(it).id })
        view.updateOrder(false,true)
        assertEquals("顺序",order.text.toString());assertFalse(order.isSelected);assertTrue(order.isEnabled)
        order.performClick();assertEquals(1,changes)
        view.updateOrder(true,true)
        assertEquals("随机",order.text.toString());assertTrue(order.isSelected)
        assertTrue(order.contentDescription.toString().contains("切换为顺序"))
        view.updateOrder(true,true,true)
        assertEquals("切换",order.text.toString());assertFalse(order.isEnabled)
        view.updateOrder(false,false)
        assertEquals("顺序",order.text.toString());assertFalse(order.isEnabled)
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun threeSecondaryButtonsRemainReadableAndStableWithLargeText() {
        for((width,font) in listOf(403 to 1f,300 to 1f,296 to 1.6f,300 to 1.6f,312 to 1.6f)) {
            RuntimeEnvironment.setQualifiers("mdpi");RuntimeEnvironment.setFontScale(font)
            val view=TrackDetailsView(RuntimeEnvironment.getApplication(),{}, {}, {})
            var original:List<android.graphics.Rect>?=null
            for((shuffled,busy) in listOf(false to false,true to false,true to true)) {
                view.update(null,null,false,LyricsState(),0,true,OfflineState.AVAILABLE,"在线")
                view.updateOrder(shuffled,true,busy)
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(280,View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,280)
                val bounds=listOf(R.id.player_lyrics_toggle,R.id.player_order_toggle,R.id.player_offline).map { id ->
                    val button=view.findViewById<TextView>(id)
                    assertTrue("Touch width at $width / $font",button.width>=48)
                    assertTrue("Touch height at $width / $font",button.height>=48)
                    assertEquals("Label must fit on one line at $width / $font",1,button.layout.lineCount)
                    assertEquals(0,button.layout.getEllipsisCount(0))
                    assertTrue(button.layout.height<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom)
                    android.graphics.Rect(button.left,button.top,button.right,button.bottom)
                }
                if(original==null)original=bounds else assertEquals("State changes must not move the row",original,bounds)
                assertTrue(bounds[0].right<bounds[1].left && bounds[1].right<bounds[2].left)
            }
        }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun plainLyricsAreScrollableAndNeverFollowPlaybackTime() {
        RuntimeEnvironment.setQualifiers("mdpi");RuntimeEnvironment.setFontScale(1.6f)
        val details=TrackDetailsView(RuntimeEnvironment.getApplication(),{}, {})
        val text=(1..60).joinToString("\n") { "合成歌词第 $it 行" }
        val state=LyricsState("song",LrcLyrics(emptyList(),text),"",true)
        fun update(show:Boolean) {
            details.update(null,null,show,state,0,true,OfflineState.LOCAL,"本地音乐")
            details.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY))
            details.layout(0,0,320,400)
        }
        update(true)
        val scroll=details.findViewById<android.widget.ScrollView>(R.id.player_lyric_scroll)
        val body=details.findViewById<TextView>(R.id.player_lyric_plain)
        assertEquals(text,body.text.toString())
        assertTrue(scroll.canScrollVertically(1))
        assertFalse(details.findViewById<View>(R.id.player_lyric_current).isShown)
        scroll.scrollTo(0,120)
        details.updateLyrics(state,45000)
        assertEquals(120,scroll.scrollY)
        update(false);update(true)
        assertEquals(120,scroll.scrollY)
        details.update(null,null,true,state.copy(key="next",lyrics=LrcLyrics(emptyList(),"Next song")),0,true,OfflineState.LOCAL,"")
        assertEquals(0,scroll.scrollY)
        assertEquals("Next song",body.text.toString())
    }
    @Test fun coverLyricsAndOfflineActionsReflectOnlyTheCurrentState() {
        val app=RuntimeEnvironment.getApplication()
        var toggles=0;var downloads=0
        val view=TrackDetailsView(app,{toggles++},{downloads++})
        val state=LyricsState("song",LrcParser.parse("[00:01]Current\n[00:03]Next"),"")
        view.update("Artist","Album",true,state,1000,true,OfflineState.DOWNLOADING,"等待下载")
        assertEquals("Artist",view.findViewById<TextView>(R.id.player_artist).text.toString())
        assertEquals("Album",view.findViewById<TextView>(R.id.player_album).text.toString())
        assertEquals("Current",view.findViewById<TextView>(R.id.player_lyric_current).text.toString())
        assertEquals("Next",view.findViewById<TextView>(R.id.player_lyric_next).text.toString())
        assertEquals(View.GONE,view.findViewById<View>(R.id.player_artwork).visibility)
        val offline=view.findViewById<TouchButton>(R.id.player_offline)
        assertEquals("下载中",offline.text.toString());assertFalse(offline.isEnabled)
        view.findViewById<View>(R.id.player_lyrics_toggle).performClick();assertEquals(1,toggles)
        view.update(null,null,false,state,0,true,OfflineState.SAVED,"可离线播放")
        assertEquals("已保留",offline.text.toString());assertFalse(offline.isEnabled)
        assertEquals(View.INVISIBLE,view.findViewById<View>(R.id.player_album).visibility)
        view.update(null,null,false,state,0,true,OfflineState.FAILED,"下载未完成")
        assertTrue(offline.isEnabled);offline.performClick();assertEquals(1,downloads)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun delayedAndMissingMetadataDoNotResizeOrMoveArtwork() {
        RuntimeEnvironment.setQualifiers("mdpi");RuntimeEnvironment.setFontScale(1f)
        val details=TrackDetailsView(RuntimeEnvironment.getApplication(),{}, {})
        fun bounds(artist:String?,album:String?):android.graphics.Rect {
            details.update(artist,album,false,LyricsState(),0,true,OfflineState.AVAILABLE,"在线")
            details.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY))
            details.layout(0,0,400,240)
            val art=details.findViewById<View>(R.id.player_artwork)
            return android.graphics.Rect(art.left,art.top,art.right,art.bottom)
        }
        val empty=bounds(null,null)
        assertEquals(empty,bounds("Artist",null))
        assertEquals(empty,bounds("Artist","Album"))
        assertEquals(empty,bounds("测试歌手","测试专辑"))
        assertEquals(empty,bounds(null,null))
    }

    @Test fun reducedMotionAndRapidArtworkChangesRetireOldLayers() {
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        try {
            val activity=controller.get()
            activity.getSharedPreferences("preferences",0).edit().putBoolean("reduceMotion",true).commit()
            val artwork=ArtworkSwitcher(activity)
            activity.setContentView(artwork,FrameLayout.LayoutParams(200,200))
            val one=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)
            val two=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)
            artwork.show("one",one);artwork.show("two",two)
            assertEquals(1,artwork.childCount);assertEquals(1f,artwork.getChildAt(0).alpha)
            activity.getSharedPreferences("preferences",0).edit().putBoolean("reduceMotion",false).commit()
            artwork.show("three",one);artwork.show("four",two)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(1,artwork.childCount)
            assertEquals(1f,artwork.getChildAt(0).alpha)
        } finally { controller.pause().stop().destroy() }
    }
}
