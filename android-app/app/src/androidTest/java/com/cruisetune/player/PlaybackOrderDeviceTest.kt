package com.cruisetune.player

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cruisetune.player.core.*
import com.cruisetune.player.playback.PlaybackService
import com.cruisetune.player.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class PlaybackOrderDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as CruiseApplication
    private fun main(block:()->Unit)=instrument.runOnMainSync(block)
    private fun await(message:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<end) {
            var done=false;main { done=condition() };if(done)return;SystemClock.sleep(50)
        }
        fail(message)
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun sessionPlayer():Player {
        // MediaController extrapolates progress. Inspect the isolated session's engine instead.
        val sessions=MediaSession::class.java.getDeclaredField("SESSION_ID_TO_SESSION_MAP").apply { isAccessible=true }.get(null) as Map<*,*>
        return sessions.values.filterIsInstance<MediaSession>().single { it.id.isEmpty() }.player
    }
    private fun assertProgressAdvances(player:Player,trackId:String) {
        var previous=0L
        main { previous=player.currentPosition }
        repeat(3) {
            SystemClock.sleep(1200)
            main {
                assertEquals(trackId,player.currentMediaItem?.mediaId)
                assertNull(player.playerError)
                assertTrue(player.isPlaying)
                val position=player.currentPosition
                assertTrue("Actual playback must keep advancing: $previous -> $position",position>=previous+300)
                previous=position
            }
        }
    }

    @Test fun secondaryOrderControlPreservesPlaybackAndReplacesSettingsEntries() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val bytes=instrument.context.assets.open("details-fixture.flac").use { it.readBytes() }
        val file=File.createTempFile("order-control-",".flac",app.cacheDir).apply { writeBytes(bytes) }
        val source=MusicSource("order-control",SourceKind.LOCAL,"Order fixtures","test-only")
        val tracks=(0 until 8).map { index -> Track("order-$index",source.id,"file-$index","Mode demo $index",
            relativePath="$index.flac",localUri=file.toURI().toString(),mimeType="audio/flac",size=file.length()) }
        val previousSort=app.preferences.getString(TrackSort.PREFERENCE,null)
        val previousLyrics=app.preferences.getBoolean("showLyrics",false)
        var activity:MainActivity?=null
        var observer:MediaController?=null
        var engine:Player?=null
        val stateChanges=mutableListOf<Int>()
        val stateListener=object:Player.Listener {
            override fun onPlaybackStateChanged(playbackState:Int) { stateChanges+=playbackState }
        }
        try {
            app.database.saveSource(source);app.database.replaceScan(source.id,tracks)
            app.preferences.edit().putString(TrackSort.PREFERENCE,TrackSort.PATH_ASC.name).putBoolean("showLyrics",false).commit()
            val screen=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=screen;runBlocking { app.library.reload() }
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            val c=future.get(10,TimeUnit.SECONDS);observer=c
            main {
                engine=sessionPlayer()
                c.setAudioAttributes(c.audioAttributes,false)
                if(screen.findViewById<View>(R.id.player_details)==null)
                    MainActivity::class.java.getDeclaredMethod("showTrackDetails").apply { isAccessible=true }.invoke(screen)
                assertFalse(screen.findViewById<View>(R.id.player_order_toggle).isEnabled)
            }
            fun command(action:String,args:Bundle) {
                lateinit var result:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                main { result=c.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args) }
                assertEquals(0,result.get(10,TimeUnit.SECONDS).resultCode)
            }
            command(PlaybackService.PLAY_TRACK,Bundle().apply { putString("trackId",tracks[2].id);putString("sourceId",source.id) })
            await("Synthetic audio must load") { c.playbackState==Player.STATE_READY && screen.findViewById<View>(R.id.player_order_toggle).isEnabled }
            main { c.pause();c.seekTo(12000);c.repeatMode=Player.REPEAT_MODE_ONE }
            await("Paused seek must finish") { engine!!.playbackState==Player.STATE_READY && engine!!.currentPosition==12000L && !engine!!.playWhenReady }
            main { screen.findViewById<View>(R.id.player_repeat_toggle).performClick() }
            await("Queue loop must be enabled independently of playback order") {
                c.repeatMode==Player.REPEAT_MODE_ALL && screen.findViewById<View>(R.id.player_repeat_toggle).isSelected
            }
            fun waitOrder(random:Boolean) = await("Mode must reflect the committed queue state") {
                c.sessionExtras.getBoolean("shuffled")==random && c.playbackState==Player.STATE_READY &&
                    screen.findViewById<TextView>(R.id.player_order_toggle).text.toString().contains(if(random)"随机"else"顺序") &&
                    screen.findViewById<View>(R.id.player_order_toggle).isEnabled
            }
            main {
                val button=screen.findViewById<TextView>(R.id.player_order_toggle)
                val row=button.parent as ViewGroup
                fun actionIds(group: ViewGroup): List<Int> = (0 until group.childCount).flatMap { child ->
                    val view=group.getChildAt(child)
                    if (view is ViewGroup) actionIds(view) else listOf(view.id)
                }
                val actions=(row.parent as? ViewGroup)?.takeIf { it.childCount == 2 && it.getChildAt(0) is ViewGroup } ?: row
                assertEquals(listOf(R.id.player_lyrics_toggle,R.id.player_order_toggle,R.id.player_repeat_toggle,R.id.player_offline),actionIds(actions))
                button.performClick();assertFalse(button.isEnabled);assertEquals("切换",button.text.toString())
                button.performClick()
            }
            waitOrder(true)
            main {
                assertEquals(tracks[2].id,c.currentMediaItem?.mediaId);assertEquals(12000L,c.currentPosition);assertFalse(c.playWhenReady)
                assertEquals(Player.REPEAT_MODE_ALL,c.repeatMode)
                assertTrue(screen.findViewById<View>(R.id.player_order_toggle).isSelected)
                screen.findViewById<View>(R.id.player_order_toggle).performClick()
            }
            waitOrder(false)
            main {
                assertEquals(tracks.map { it.id },(0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
                assertEquals(12000L,c.currentPosition);assertFalse(c.playWhenReady)
                c.play()
            }
            await("Synthetic audio must play") { engine!!.isPlaying }
            assertProgressAdvances(engine!!,tracks[2].id)
            main { engine!!.addListener(stateListener) }
            for(random in listOf(true,false)) {
                main { stateChanges.clear();screen.findViewById<View>(R.id.player_order_toggle).performClick() }
                waitOrder(random)
                assertProgressAdvances(engine!!,tracks[2].id)
                main {
                    assertFalse("Changing order must not discard the active buffer",Player.STATE_BUFFERING in stateChanges)
                    assertFalse("Changing order must not stop playback",Player.STATE_IDLE in stateChanges)
                    assertEquals(Player.REPEAT_MODE_ALL,engine!!.repeatMode)
                }
            }
            main { c.pause();c.seekTo(18000) }
            await("Paused retry position must be applied") { engine!!.playbackState==Player.STATE_READY && engine!!.currentPosition==18000L && !engine!!.playWhenReady }
            main { stateChanges.clear() }
            command(PlaybackService.RETRY,Bundle.EMPTY)
            await("Explicit retry must resume playback") { engine!!.isPlaying }
            main {
                assertTrue("Retry must stop the old load",Player.STATE_IDLE in stateChanges)
                assertTrue("Retry must prepare a new load",Player.STATE_BUFFERING in stateChanges)
                assertEquals(tracks.map { it.id },(0 until engine!!.mediaItemCount).map { engine!!.getMediaItemAt(it).mediaId })
                assertEquals(Player.REPEAT_MODE_ALL,engine!!.repeatMode)
                assertTrue(engine!!.currentPosition>=18000)
            }
            assertProgressAdvances(engine!!,tracks[2].id)
            main { c.pause();engine!!.removeListener(stateListener) }
            instrument.waitForIdleSync()
            main {
                for(id in listOf(R.id.player_lyrics_toggle,R.id.player_order_toggle,R.id.player_repeat_toggle,R.id.player_offline,R.id.player_previous,R.id.player_play,R.id.player_next)) {
                    val view=screen.findViewById<View>(id);val visible=Rect()
                    assertTrue(view.getGlobalVisibleRect(visible));assertEquals(view.width,visible.width());assertEquals(view.height,visible.height())
                    assertTrue(view.width>=48*view.resources.displayMetrics.density)
                    assertTrue(view.height>=48*view.resources.displayMetrics.density)
                    if(id in listOf(R.id.player_lyrics_toggle,R.id.player_order_toggle,R.id.player_repeat_toggle,R.id.player_offline)) {
                        val label=view as TextView
                        assertEquals("Secondary label must fit",1,label.layout.lineCount)
                        assertEquals("Secondary label must not be truncated",0,label.layout.getEllipsisCount(0))
                        assertTrue(label.layout.height<=label.height-label.compoundPaddingTop-label.compoundPaddingBottom)
                    }
                }
            }
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.getExternalFilesDir(null),"playback-order.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            command(PlaybackService.SORT_QUEUE,Bundle().apply { putString("sort",TrackSort.TITLE_DESC.name) })
            waitOrder(false)
            main { views(screen.window.decorView).filterIsInstance<TextView>().single { it.text.toString()=="设置" }.performClick() }
            instrument.waitForIdleSync()
            val root=instrument.uiAutomation.rootInActiveWindow
            assertTrue(root.findAccessibilityNodeInfosByText("随机播放").none { it.isClickable })
            assertTrue(root.findAccessibilityNodeInfosByText("播放顺序：").none { it.isClickable })
            assertTrue(root.findAccessibilityNodeInfosByText("列表循环").none { it.isClickable })
            root.findAccessibilityNodeInfosByText("完成").first { it.text?.toString()=="完成" && it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            assertArrayEquals(bytes,file.readBytes())
        } finally {
            main { engine?.removeListener(stateListener);observer?.let { it.pause();it.setAudioAttributes(it.audioAttributes,true);it.release() };activity?.finish() }
            runBlocking { app.library.removeSources(setOf(source.id),PlaybackSnapshot()) }
            app.preferences.edit().putBoolean("showLyrics",previousLyrics).apply {
                if(previousSort==null)remove(TrackSort.PREFERENCE)else putString(TrackSort.PREFERENCE,previousSort)
            }.commit()
            file.delete()
        }
    }
}
