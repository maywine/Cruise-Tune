package com.cruisetune.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cruisetune.player.core.*
import com.cruisetune.player.playback.PlaybackService
import com.cruisetune.player.ui.MainActivity
import com.cruisetune.player.ui.TrackAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Long synthetic queue; no real sources, credentials or audio focus changes. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class QueueFollowDeviceTest {
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
    @Test fun queueFollowsPlaybackWithoutStealingLibraryOrManualBrowsingPosition() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val bytes=instrument.context.assets.open("details-fixture.flac").use { it.readBytes() }
        val file=File.createTempFile("queue-follow-",".flac",app.cacheDir).apply { writeBytes(bytes) }
        val source=MusicSource("queue-follow-source",SourceKind.LOCAL,"Queue follow fixtures","test-only")
        val tracks=(0 until 120).map { index -> Track("follow-$index",source.id,"file-$index","Song %03d".format(index),
            relativePath="%03d.flac".format(index),localUri=file.toURI().toString(),mimeType="audio/flac",size=file.length()) }
        val previousSort=app.preferences.getString(TrackSort.PREFERENCE,null)
        val previousLyrics=app.preferences.getBoolean("showLyrics",false)
        var activity:MainActivity?=null
        var observer:MediaController?=null
        try {
            app.database.saveSource(source);app.database.replaceScan(source.id,tracks)
            app.preferences.edit().putString(TrackSort.PREFERENCE,TrackSort.PATH_ASC.name).putBoolean("showLyrics",false).commit()
            val screen=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=screen
            runBlocking { app.library.reload() }
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            val c=future.get(10,TimeUnit.SECONDS);observer=c
            main { c.setAudioAttributes(c.audioAttributes,false) }
            fun list()=screen.findViewById<RecyclerView>(R.id.player_list)
            fun layout()=list().layoutManager as LinearLayoutManager
            fun first()=layout().findFirstVisibleItemPosition()
            fun tab(name:String) = main { (MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(screen) as View).performClick() }
            fun browse(index:Int) { main { layout().scrollToPositionWithOffset(index,0) };await("Manual browsing must reach $index") { first()==index } }
            fun command(action:String,args:Bundle=Bundle.EMPTY) {
                lateinit var result:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                main { result=c.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args) }
                assertEquals(0,result.get(10,TimeUnit.SECONDS).resultCode)
            }
            fun currentVisible() = await("The playing queue row must be fully visible and selected") {
                val adapter=list().adapter as TrackAdapter
                val index=adapter.currentList.indexOfFirst { it.id==c.currentMediaItem?.mediaId }
                index>=0 && index>=layout().findFirstCompletelyVisibleItemPosition() && index<=layout().findLastCompletelyVisibleItemPosition() &&
                    list().findViewHolderForAdapterPosition(index)?.itemView?.isSelected==true
            }
            await("Library must contain all synthetic songs") { list().adapter?.itemCount==120 }
            browse(30)
            command(PlaybackService.PLAY_TRACK,Bundle().apply { putString("trackId",tracks[70].id);putString("sourceId",source.id) })
            await("Selected audio must load") { c.currentMediaItem?.mediaId==tracks[70].id && c.playbackState==Player.STATE_READY }
            main { c.pause();assertEquals(30,first()) }
            tab("queueTab");currentVisible()
            browse(5)
            main {
                repeat(3) { MainActivity::class.java.getDeclaredMethod("renderList").apply { isAccessible=true }.invoke(screen) }
                c.seekTo(1000)
            }
            SystemClock.sleep(350);main { assertEquals("Status and seek updates must not override browsing",5,first()) }
            tab("queueTab");currentVisible()
            browse(5)
            main { screen.findViewById<View>(R.id.player_next).performClick() }
            await("Next song must be selected") { c.currentMediaItem?.mediaId==tracks[71].id };currentVisible()
            main { screen.findViewById<View>(R.id.player_previous).performClick() }
            await("Previous song must be selected") { c.currentMediaItem?.mediaId==tracks[70].id };currentVisible()

            browse(5)
            command(PlaybackService.SORT_QUEUE,Bundle().apply { putString("sort",TrackSort.TITLE_DESC.name) })
            await("Queue order must change") { c.currentMediaItemIndex==49 && c.currentMediaItem?.mediaId==tracks[70].id };currentVisible()
            browse(5)
            main { MainActivity::class.java.getDeclaredMethod("showTrackDetails").apply { isAccessible=true }.invoke(screen) }
            main { screen.findViewById<View>(R.id.player_details_navigation).performClick() }
            await("Returning without a track change must restore browsing") { first()==5 }
            main { MainActivity::class.java.getDeclaredMethod("showTrackDetails").apply { isAccessible=true }.invoke(screen) }
            main { screen.findViewById<View>(R.id.player_next).performClick() }
            await("Song must change while details are expanded") { c.currentMediaItemIndex==50 }
            main { screen.findViewById<View>(R.id.player_details_navigation).performClick() };currentVisible()

            browse(5)
            val expectedNext=tracks[68].id
            main { c.seekTo(59950);c.play() }
            await("Playback must naturally advance") { c.currentMediaItem?.mediaId==expectedNext };currentVisible()
            main { c.pause() }
            tab("libraryTab")
            await("Library browsing position must survive queue following") { first()==30 }
            main { assertEquals(tracks[30].id,(list().adapter as TrackAdapter).currentList[first()].id) }

            // Exercise a real library row click and the asynchronous library-to-queue switch.
            main { checkNotNull(list().findViewHolderForAdapterPosition(30)).itemView.performClick() }
            await("Library selection must establish a new queue") { c.currentMediaItem?.mediaId==tracks[30].id }
            currentVisible();main { c.pause() }
            command(PlaybackService.REMOVE_SOURCE,Bundle().apply { putString("sourceId",source.id) })
            await("An emptied queue must stay empty") { c.mediaItemCount==0 && list().adapter?.itemCount==0 }
            assertArrayEquals(bytes,file.readBytes())
        } finally {
            main { observer?.let { it.pause();it.setAudioAttributes(it.audioAttributes,true);it.release() };activity?.finish() }
            runBlocking { app.library.removeSources(setOf(source.id),PlaybackSnapshot()) }
            app.preferences.edit().putBoolean("showLyrics",previousLyrics).apply {
                if(previousSort==null)remove(TrackSort.PREFERENCE)else putString(TrackSort.PREFERENCE,previousSort)
            }.commit()
            file.delete()
        }
    }
}
