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
