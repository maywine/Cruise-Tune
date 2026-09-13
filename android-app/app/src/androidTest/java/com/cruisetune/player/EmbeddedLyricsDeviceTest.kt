package com.cruisetune.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cruisetune.player.core.*
import com.cruisetune.player.data.LibraryRepository
import com.cruisetune.player.playback.PlaybackService
import com.cruisetune.player.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Generated silence and synthetic words only, inside the isolated validation package. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class EmbeddedLyricsDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val app=instrument.targetContext.applicationContext as CruiseApplication
    private fun onMain(block:()->Unit)=instrument.runOnMainSync(block)
    private fun usltFixture(mp3:ByteArray):ByteArray {
        fun size(value:Int)=byteArrayOf((value ushr 21).toByte(),((value ushr 14) and 127).toByte(),
            ((value ushr 7) and 127).toByte(),(value and 127).toByte())
        check(String(mp3,0,3,Charsets.US_ASCII)=="ID3")
        val oldSize=(6..9).fold(0) { value,index -> (value shl 7) or (mp3[index].toInt() and 127) }
        val payload=byteArrayOf(3)+"zho".toByteArray()+byteArrayOf(0)+
            "[00:01]内嵌第一句\n[00:04]内嵌第二句\n[00:08]内嵌第三句".toByteArray(Charsets.UTF_8)
        val frame="USLT".toByteArray()+size(payload.size)+byteArrayOf(0,0)+payload
        // Construct an actual ID3v2.4 USLT frame; FFmpeg may write a TXXX field named USLT.
        return "ID3".toByteArray()+byteArrayOf(4,0,0)+size(frame.size)+frame+mp3.copyOfRange(10+oldSize,mp3.size)
    }
    private fun await(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<deadline) {
            var ready=false;onMain { ready=condition() }
            if(ready)return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    @Test fun embeddedTagsPreferFileLyricsAndKeepPlainTextAndOfflinePlaybackUsable() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val assets=listOf("embedded-synced.flac","embedded-plain.flac","embedded.mp3","embedded.m4a","embedded.ogg","details-fixture.flac")
        val fixtures=assets.associateWith { instrument.context.assets.open(it).use { stream -> stream.readBytes() } }.toMutableMap().apply {
            put("embedded-uslt.mp3",usltFixture(getValue("embedded.mp3")))
        }
        val folder=File(app.cacheDir,"embedded-lyrics-${System.nanoTime()}").apply { check(mkdir()) }
        val source=MusicSource("embedded-local",SourceKind.LOCAL,"Embedded fixtures","test-only")
        val remoteSource=MusicSource("embedded-remote",SourceKind.QUARK,"Offline fixture","test-only","synthetic")
        val tracks=fixtures.entries.mapIndexed { index,(name,bytes) ->
            val file=File(folder,name).apply { writeBytes(bytes) }
            File(folder,name.substringBeforeLast('.')+".lrc").writeText("[00:01]同目录回退歌词\n[00:04]回退第二句")
            Track("embedded-$index",source.id,"file-$index","Embedded demo $index",relativePath=name,
                localUri=file.toURI().toString(),size=file.length(),mimeType=when(file.extension) {
                    "mp3" -> "audio/mpeg";"m4a" -> "audio/mp4";"ogg" -> "audio/ogg";else -> "audio/flac"
                })
        }
        val remote=tracks.first().copy(id="embedded-offline",sourceId=remoteSource.id,localUri="")
        val factoryField=LibraryRepository::class.java.getDeclaredField("providerFactory").apply { isAccessible=true }
        val previousFactory=factoryField.get(app.library)
        val previousLyrics=app.preferences.getBoolean("showLyrics",false)
        var previousRequirements:Requirements?=null
        var activity:MainActivity?=null
        var controller:MediaController?=null
        try {
            app.database.saveSource(source);app.database.replaceScan(source.id,tracks)
            app.database.saveSource(remoteSource);app.database.replaceScan(remoteSource.id,listOf(remote))
            app.preferences.edit().putBoolean("showLyrics",false).commit()
            val provider=object:MusicProvider {
                override suspend fun listChildren(parentId:String)=emptyList<RemoteEntry>()
                override suspend fun resolve(fileId:String)=ReadRequest(File(folder,assets.first()).toURI().toString(),emptyMap())
            }
            val factory:(SourceKind,String)->MusicProvider={_,_->provider}
            factoryField.set(app.library,factory)
            onMain { previousRequirements=app.media.downloadManager.requirements;app.media.downloadManager.requirements=Requirements(0) }
            val a=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=a
            runBlocking { app.library.reload() }
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            onMain { future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            val c=future.get(10,TimeUnit.SECONDS);controller=c
            fun play(track:Track) {
                lateinit var result:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                onMain { result=c.sendCustomCommand(SessionCommand(PlaybackService.PLAY_TRACK,Bundle.EMPTY),Bundle().apply {
                    putString("trackId",track.id);putString("sourceId",track.sourceId)
                }) }
                assertEquals(0,result.get(10,TimeUnit.SECONDS).resultCode)
                await("${track.relativePath} must load") { c.currentMediaItem?.mediaId==track.id && c.playbackState==Player.STATE_READY }
                onMain {
                    c.pause();c.seekTo(1000)
                    if(a.findViewById<View>(R.id.player_details)==null)
                        MainActivity::class.java.getDeclaredMethod("showTrackDetails").apply { isAccessible=true }.invoke(a)
                    if(a.findViewById<TextView>(R.id.player_lyrics_toggle).text.toString()=="歌词")
                        a.findViewById<View>(R.id.player_lyrics_toggle).performClick()
                }
            }
            for(track in tracks.filterNot { it.relativePath=="embedded-plain.flac" || it.relativePath=="details-fixture.flac" }) {
                play(track)
                await("${track.relativePath} must prefer embedded lyrics to its sidecar") {
                    a.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="内嵌第一句"
                }
                onMain { c.seekTo(5000) }
                await("${track.relativePath} paused seek must synchronize") { a.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="内嵌第二句" }
                onMain { c.seekTo(1000) }
                await("${track.relativePath} backward seek must synchronize") { a.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="内嵌第一句" }
            }
            play(tracks.first { it.relativePath=="embedded-plain.flac" })
            await("Plain embedded lyrics must remain readable") {
                a.findViewById<TextView>(R.id.player_lyric_plain).text.toString().contains("合成纯文本歌词第 60 行") &&
                    a.findViewById<View>(R.id.player_lyric_plain).isShown
            }
            onMain {
                for(id in listOf(R.id.player_title,R.id.player_seek,R.id.player_previous,R.id.player_play,R.id.player_next,R.id.player_lyric_scroll)) {
                    val view=a.findViewById<View>(id);val visible=android.graphics.Rect()
                    assertTrue("Control must be visible: $id",view.getGlobalVisibleRect(visible))
                    assertEquals("Control must not be clipped: $id",view.height,visible.height())
                    assertEquals("Control must not be clipped: $id",view.width,visible.width())
                }
                assertTrue("Plain lyrics need at least one full line",a.findViewById<View>(R.id.player_lyric_scroll).height >= a.findViewById<TextView>(R.id.player_lyric_plain).lineHeight)
            }
            instrument.waitForIdleSync()
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.getExternalFilesDir(null),"embedded-plain.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            var scrollPosition=0
            onMain {
                val scroll=a.findViewById<ScrollView>(R.id.player_lyric_scroll)
                assertTrue(scroll.canScrollVertically(1));scroll.scrollTo(0,120);scrollPosition=scroll.scrollY
                c.seekTo(10000)
                a.findViewById<View>(R.id.player_lyrics_toggle).performClick()
                a.findViewById<View>(R.id.player_lyrics_toggle).performClick()
            }
            instrument.waitForIdleSync()
            onMain {
                assertEquals(scrollPosition,a.findViewById<ScrollView>(R.id.player_lyric_scroll).scrollY)
                assertTrue(a.findViewById<View>(R.id.player_play).isShown)
                assertTrue(a.findViewById<View>(R.id.player_seek).isShown)
            }
            play(tracks.first { it.relativePath=="details-fixture.flac" })
            await("A song without embedded lyrics must use its sidecar") { a.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="同目录回退歌词" }
            onMain { assertFalse(a.findViewById<View>(R.id.player_lyric_plain).isShown) }
            play(remote)
            await("Provider-backed files must expose embedded lyrics") { a.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="内嵌第一句" }
            onMain { app.media.keepOffline(remote) }
            await("Synthetic offline file must finish") { app.media.isComplete(remote) }
            val unavailable:(SourceKind,String)->MusicProvider={_,_->error("Offline playback must not resolve a provider")}
            factoryField.set(app.library,unavailable)
            onMain { c.stop();c.prepare();c.seekTo(5000) }
            await("Retained audio must load without the provider") { c.playbackState==Player.STATE_READY }
            await("Offline lyrics must stay synchronized") { a.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="内嵌第二句" }
            for((name,bytes) in fixtures) assertArrayEquals("Audio files must not be rewritten",bytes,File(folder,name).readBytes())
        } finally {
            onMain { controller?.release();activity?.finish();app.media.removeOffline(remote);previousRequirements?.let { app.media.downloadManager.requirements=it } }
            runBlocking { app.library.removeSources(setOf(source.id,remoteSource.id),PlaybackSnapshot()) }
            factoryField.set(app.library,previousFactory)
            app.preferences.edit().putBoolean("showLyrics",previousLyrics).commit()
            folder.deleteRecursively()
        }
    }
}
