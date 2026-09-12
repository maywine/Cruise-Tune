package com.cruisetune.player

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.SeekBar
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cruisetune.player.core.*
import com.cruisetune.player.data.LibraryRepository
import com.cruisetune.player.playback.PlaybackService
import com.cruisetune.player.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Uses a generated FLAC with Demo Artist / Demo Album tags and an embedded test cover. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class PlayerDetailsDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as CruiseApplication
    private fun onMain(block:()->Unit)=instrument.runOnMainSync(block)
    private fun capture(name:String) {
        instrument.waitForIdleSync();SystemClock.sleep(100)
        val bitmap=instrument.uiAutomation.takeScreenshot() ?: return
        File(app.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun await(message:String, condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<deadline) {
            var ready=false;onMain { ready=condition() }
            if(ready)return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    @Test fun tagsSidecarLyricsAndOfflineDownloadWorkThroughThePlayer() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val fixture=instrument.context.assets.open("details-fixture.flac").use { it.readBytes() }
        val audio=File.createTempFile("details-fixture-",".flac",app.cacheDir).apply { writeBytes(fixture) }
        val lrc=File(audio.parentFile,audio.nameWithoutExtension+".lrc")
        lrc.writeText("[00:00]合成第一句\n[00:04]合成第二句\n[00:08]合成第三句")
        val localSource=MusicSource("details-local",SourceKind.LOCAL,"Folder label","test-only")
        val remoteSource=MusicSource("details-remote",SourceKind.QUARK,"Offline fixture","test-only","synthetic")
        val local=Track("details-local-track",localSource.id,"fixture","Demo Track",artist="Folder label",
            relativePath=audio.name,size=audio.length(),localUri=audio.toURI().toString(),mimeType="audio/flac")
        val remote=local.copy(id="details-remote-track",sourceId=remoteSource.id,localUri="")
        val second=local.copy(id=local.id+"-2",title="Demo Track 2")
        app.database.saveSource(localSource);app.database.replaceScan(localSource.id,listOf(local,second))
        app.database.saveSource(remoteSource);app.database.replaceScan(remoteSource.id,listOf(remote))
        val factoryField=LibraryRepository::class.java.getDeclaredField("providerFactory").apply { isAccessible=true }
        val previousFactory=factoryField.get(app.library)
        val provider=object:MusicProvider {
            override suspend fun listChildren(parentId:String)=emptyList<RemoteEntry>()
            override suspend fun resolve(fileId:String):ReadRequest {
                delay(1800)
                return ReadRequest(audio.toURI().toString(),emptyMap())
            }
        }
        val factory:(SourceKind,String)->MusicProvider={_,_->provider}
        factoryField.set(app.library,factory)
        val previousLyrics=app.preferences.getBoolean("showLyrics",false)
        var previousRequirements:Requirements?=null
        onMain {
            previousRequirements=app.media.downloadManager.requirements
            // The provider serves an app-owned fixture file, so this test requires no network.
            app.media.downloadManager.requirements=Requirements(0)
        }
        app.preferences.edit().putBoolean("showLyrics",false).commit()
        var controller:MediaController?=null
        val activity=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            runBlocking { app.library.reload() }
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            onMain { future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            val c=future.get(10,TimeUnit.SECONDS);controller=c
            fun ensureDetails() = onMain {
                if(activity.findViewById<View>(R.id.player_details)==null)
                    MainActivity::class.java.getDeclaredMethod("showTrackDetails").apply { isAccessible=true }.invoke(activity)
            }
            fun play(track:Track) {
                lateinit var result:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                onMain { result=c.sendCustomCommand(SessionCommand(PlaybackService.PLAY_TRACK,Bundle.EMPTY),Bundle().apply { putString("trackId",track.id);putString("sourceId",track.sourceId) }) }
                assertEquals(0,result.get(10,TimeUnit.SECONDS).resultCode)
                await("FLAC should be ready") { c.playbackState==Player.STATE_READY }
            }
            play(local)
            await("Artist, album and cover must come from FLAC metadata") {
                c.mediaMetadata.artist?.toString()=="Demo Artist" && c.mediaMetadata.albumTitle?.toString()=="Demo Album" && c.mediaMetadata.artworkData != null
            }
            ensureDetails()
            onMain {
                assertEquals("Demo Artist",activity.findViewById<TextView>(R.id.player_artist).text.toString())
                assertEquals("Demo Album",activity.findViewById<TextView>(R.id.player_album).text.toString())
            }
            SystemClock.sleep(200);capture("details-cover.png")
            onMain {
                c.pause();c.seekTo(1000)
                activity.findViewById<View>(R.id.player_lyrics_toggle).performClick()
            }
            await("LRC current and next cues should be visible") {
                activity.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="合成第一句" &&
                    activity.findViewById<TextView>(R.id.player_lyric_next).text.toString()=="合成第二句"
            }
            capture("details-lyrics.png")
            val lyricText=lrc.readText()
            assertTrue(lrc.delete())
            onMain { activity.findViewById<View>(R.id.player_lyrics_toggle).performClick() }
            instrument.waitForIdleSync()
            onMain { activity.findViewById<View>(R.id.player_lyrics_toggle).performClick() }
            instrument.waitForIdleSync();SystemClock.sleep(300)
            onMain { assertEquals("合成第一句",activity.findViewById<TextView>(R.id.player_lyric_current).text.toString()) }
            lrc.writeText(lyricText)

            val preview=MainActivity::class.java.getDeclaredMethod("previewSeek",Int::class.javaPrimitiveType).apply { isAccessible=true }
            val finish=MainActivity::class.java.getDeclaredMethod("finishSeek",Int::class.javaPrimitiveType).apply { isAccessible=true }
            val redraw=MainActivity::class.java.getDeclaredMethod("renderPlayer").apply { isAccessible=true }
            onMain {
                activity.findViewById<SeekBar>(R.id.player_seek).progress=1000
                preview.invoke(activity,1000)
                redraw.invoke(activity)
                assertEquals("合成第二句",activity.findViewById<TextView>(R.id.player_lyric_current).text.toString())
                assertEquals(1000L,c.currentPosition)
                finish.invoke(activity,1000)
            }
            await("Releasing the preview must commit the seek") { c.currentPosition==6000L }
            onMain { c.seekTo(5000) }
            await("Seeking must update paused lyrics") { activity.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="合成第二句" }
            onMain { c.seekTo(1000) }
            await("Seeking backwards must restore the earlier cue") { activity.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="合成第一句" }
            var originalInset=0
            onMain {
                originalInset=activity.findViewById<View>(R.id.player_root).paddingTop
                MainActivity::class.java.getDeclaredMethod("showTrackDetails").apply { isAccessible=true }.invoke(activity)
                assertEquals("Demo Track",activity.findViewById<TextView>(R.id.player_title).text.toString())
                activity.findViewById<View>(R.id.player_play).performClick()
            }
            await("Expanded details must retain system-bar insets") { activity.findViewById<View>(R.id.player_root).paddingTop==originalInset }
            await("Expanded details must allow playing directly") { c.playWhenReady }
            onMain { activity.findViewById<View>(R.id.player_play).performClick() }
            await("Expanded details must allow pausing directly") { !c.playWhenReady }
            onMain { activity.findViewById<View>(R.id.player_next).performClick() }
            await("Expanded details must allow the next track") { c.currentMediaItem?.mediaId==second.id }
            onMain { activity.findViewById<View>(R.id.player_previous).performClick() }
            await("Expanded details must allow the previous track") { c.currentMediaItem?.mediaId==local.id }
            capture("details-expanded.png")
            onMain { activity.findViewById<View>(R.id.player_details_navigation).performClick() }
            onMain { assertNotNull(activity.findViewById<View>(R.id.player_list)) }
            ensureDetails()
            onMain {
                assertEquals("本地音乐",activity.findViewById<TextView>(R.id.player_offline).text.toString())
                assertFalse(activity.findViewById<View>(R.id.player_offline).isEnabled)
                activity.findViewById<View>(R.id.player_lyrics_toggle).performClick()
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.player_artwork).visibility)
            }
            play(remote)
            onMain { c.pause() }
            await("Stream cache must still offer explicit offline retention") { activity.findViewById<TextView>(R.id.player_offline).text.toString()=="保留离线" }
            onMain {
                activity.findViewById<View>(R.id.player_offline).performClick()
                assertEquals("下载中",activity.findViewById<TextView>(R.id.player_offline).text.toString())
                assertFalse(activity.findViewById<View>(R.id.player_offline).isEnabled)
            }
            try {
                await("The finished offline download must be shown as retained") { activity.findViewById<TextView>(R.id.player_offline).text.toString()=="已保留" }
            } catch (failure:AssertionError) {
                val download=app.offlineIndex.getDownload(remote.cacheKey)
                var detail=""
                onMain { detail="label=${activity.findViewById<TextView>(R.id.player_offline).text}, unmet=${app.media.downloadManager.notMetRequirements}" }
                throw AssertionError("$detail, state=${download?.state}, bytes=${download?.bytesDownloaded}, complete=${app.media.isComplete(remote)}",failure)
            }
            assertTrue(app.media.isComplete(remote))
            capture("details-offline.png")
            onMain { assertFalse(c.playWhenReady);assertEquals(remote.id,c.currentMediaItem!!.mediaId) }
        } finally {
            onMain { controller?.release();activity.finish();app.media.removeOffline(remote);previousRequirements?.let { app.media.downloadManager.requirements=it } }
            runBlocking { app.library.removeSources(setOf(localSource.id,remoteSource.id),PlaybackSnapshot()) }
            factoryField.set(app.library,previousFactory)
            app.preferences.edit().putBoolean("showLyrics",previousLyrics).commit()
            lrc.delete();audio.delete()
        }
    }
}
