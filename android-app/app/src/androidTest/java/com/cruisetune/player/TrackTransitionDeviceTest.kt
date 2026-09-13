package com.cruisetune.player

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.RecyclerView
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Distinct synthetic tags expose stale data that identical fixtures cannot reveal. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class TrackTransitionDeviceTest {
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
    private fun size(value:Int)=byteArrayOf((value ushr 21).toByte(),((value ushr 14) and 127).toByte(),
        ((value ushr 7) and 127).toByte(),(value and 127).toByte())
    private fun frame(id:String,bytes:ByteArray)=id.toByteArray(Charsets.US_ASCII)+size(bytes.size)+byteArrayOf(0,0)+bytes
    private fun blueTaggedMp3(body:ByteArray):ByteArray {
        val image=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val picture=ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray() }
        image.recycle()
        val frames=frame("USLT",byteArrayOf(3)+"zho\u0000[00:01]第三首自己的歌词\n[00:04]第三首下一句".toByteArray(Charsets.UTF_8))+
            frame("APIC",byteArrayOf(3)+"image/png\u0000".toByteArray()+byteArrayOf(3,0)+picture)+
            frame("TPE1",byteArrayOf(3)+"Third Artist".toByteArray())
        return "ID3".toByteArray()+byteArrayOf(4,0,0)+size(frames.size)+frames+body
    }

    @Test fun playbackControlsNeverAttachPreviousTagsToTheNewSong() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val firstBytes=instrument.context.assets.open("embedded-synced.flac").use { it.readBytes() }
        val mp3=instrument.context.assets.open("embedded.mp3").use { it.readBytes() }
        check(String(mp3,0,3)=="ID3")
        val headerSize=(6..9).fold(0) { value,index -> (value shl 7) or (mp3[index].toInt() and 127) }
        val body=mp3.copyOfRange(10+headerSize,mp3.size)
        val folder=File(app.cacheDir,"transition-fixtures-${System.nanoTime()}").apply { check(mkdir()) }
        val firstFile=File(folder,"A.flac").apply { writeBytes(firstBytes) }
        val bareFile=File(folder,"B.mp3").apply { writeBytes(body) }
        val thirdFile=File(folder,"C.mp3").apply { writeBytes(blueTaggedMp3(body)) }
        val source=MusicSource("transition-source",SourceKind.QUARK,"Transition fixtures","test-only","synthetic")
        val first=Track("transition-A",source.id,"a","Synthetic A",relativePath="A.flac",localUri=firstFile.toURI().toString(),mimeType="audio/flac",size=firstFile.length())
        val bare=Track("transition-B",source.id,"b","Synthetic B",relativePath="B.mp3",mimeType="audio/mpeg",size=bareFile.length())
        val third=Track("transition-C",source.id,"c","Synthetic C",relativePath="C.mp3",localUri=thirdFile.toURI().toString(),mimeType="audio/mpeg",size=thirdFile.length())
        val factoryField=LibraryRepository::class.java.getDeclaredField("providerFactory").apply { isAccessible=true }
        val previousFactory=factoryField.get(app.library)
        val previousLyrics=app.preferences.getBoolean("showLyrics",false)
        val previousSort=app.preferences.getString(TrackSort.PREFERENCE,null)
        var activity:MainActivity?=null
        var observer:MediaController?=null
        try {
            val provider=object:MusicProvider {
                override suspend fun listChildren(parentId:String)=emptyList<RemoteEntry>()
                override suspend fun resolve(fileId:String):ReadRequest { delay(1500);return ReadRequest(bareFile.toURI().toString(),emptyMap()) }
            }
            val factory:(SourceKind,String)->MusicProvider={_,_->provider}
            factoryField.set(app.library,factory)
            app.database.saveSource(source);app.database.replaceScan(source.id,listOf(first,bare,third))
            app.preferences.edit().putBoolean("showLyrics",false).putString(TrackSort.PREFERENCE,TrackSort.PATH_ASC.name).commit()
            val screen=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=screen
            runBlocking { app.library.reload() }
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync() }
            val c=future.get(10,TimeUnit.SECONDS);observer=c
            val uiController=MainActivity::class.java.getDeclaredField("controller").apply { isAccessible=true }.get(screen) as MediaController
            main { c.setAudioAttributes(c.audioAttributes,false) }
            lateinit var result:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
            main { result=c.sendCustomCommand(SessionCommand(PlaybackService.PLAY_TRACK,Bundle.EMPTY),Bundle().apply { putString("trackId",first.id);putString("sourceId",source.id) }) }
            assertEquals(0,result.get(10,TimeUnit.SECONDS).resultCode)
            fun ensureDetails() {
                if(screen.findViewById<View>(R.id.player_details)==null)
                    MainActivity::class.java.getDeclaredMethod("showTrackDetails").apply { isAccessible=true }.invoke(screen)
            }
            fun artwork()=MainActivity::class.java.getDeclaredField("artworkBitmap").apply { isAccessible=true }.get(screen) as Bitmap?
            fun ready(track:Track) = await("${track.title} must become ready") {
                c.currentMediaItem?.mediaId==track.id && c.playbackState==Player.STATE_READY && app.playbackMetadata.value.forTrack(track.id)?.lyrics!=null
            }
            fun firstVisible() {
                ready(first);main { ensureDetails();c.pause();c.seekTo(1000) }
                await("First song must show its own tags") {
                    screen.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="内嵌第一句" && artwork()!=null
                }
            }
            fun noPreviousTags() {
                assertNull("A new untagged track must not retain artwork",artwork())
                assertEquals("",screen.findViewById<TextView>(R.id.player_artist).text.toString())
                val text=screen.findViewById<TextView>(R.id.player_lyric_current).text.toString()
                assertFalse(text.contains("内嵌第一句") || text.contains("第三首"))
            }
            fun bareVisible() {
                ready(bare)
                await("Untagged MP3 must not use cached embedded lyrics") {
                    screen.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="未找到内嵌歌词或同名 .lrc 歌词"
                }
                main { noPreviousTags();assertNull(c.mediaMetadata.artworkData) }
            }
            fun bareSelected() {
                // onEvents renders on the next looper iteration, not inside performClick().
                await("The selected title must update") { screen.findViewById<TextView>(R.id.player_title).text.toString()==bare.title }
                main { noPreviousTags() }
            }
            fun thirdVisible() {
                ready(third);main { ensureDetails();c.seekTo(1000) }
                await("Third song must display its different cover and lyrics") {
                    screen.findViewById<TextView>(R.id.player_lyric_current).text.toString()=="第三首自己的歌词" && artwork()?.getPixel(0,0)==Color.BLUE
                }
            }
            ready(first)
            main { c.pause();ensureDetails();screen.findViewById<View>(R.id.player_lyrics_toggle).performClick() }
            firstVisible()
            main { screen.findViewById<View>(R.id.player_next).performClick() }
            bareSelected()
            bareVisible()
            main { screen.findViewById<View>(R.id.player_next).performClick() }
            thirdVisible()
            main { screen.findViewById<View>(R.id.player_previous).performClick() }
            bareSelected()
            bareVisible()
            main { screen.findViewById<View>(R.id.player_previous).performClick() }
            firstVisible()
            main { screen.findViewById<View>(R.id.player_next).performClick() }
            await("Previous command must reflect the newly selected item") {
                uiController.currentMediaItem?.mediaId==bare.id && uiController.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
            main { screen.findViewById<View>(R.id.player_previous).performClick() }
            instrument.waitForIdleSync();firstVisible()

            main {
                screen.findViewById<TextView>(R.id.player_details_navigation)?.takeIf { it.text.toString()=="返回" }?.performClick()
                (MainActivity::class.java.getDeclaredField("queueTab").apply { isAccessible=true }.get(screen) as View).performClick()
            }
            instrument.waitForIdleSync()
            main { screen.findViewById<RecyclerView>(R.id.player_list).scrollToPosition(2) }
            instrument.waitForIdleSync()
            main { checkNotNull(screen.findViewById<RecyclerView>(R.id.player_list).findViewHolderForAdapterPosition(2)).itemView.performClick() }
            thirdVisible()

            main { c.repeatMode=Player.REPEAT_MODE_OFF;c.seekTo(0,59950);c.play() }
            await("Natural track completion must advance to B") { c.currentMediaItem?.mediaId==bare.id }
            main { c.pause() };bareVisible()
            assertArrayEquals(firstBytes,firstFile.readBytes());assertArrayEquals(body,bareFile.readBytes())
        } finally {
            main { observer?.let { it.pause();it.setAudioAttributes(it.audioAttributes,true);it.release() };activity?.finish() }
            runBlocking { app.library.removeSources(setOf(source.id),PlaybackSnapshot()) }
            factoryField.set(app.library,previousFactory)
            app.preferences.edit().putBoolean("showLyrics",previousLyrics).apply {
                if(previousSort==null)remove(TrackSort.PREFERENCE)else putString(TrackSort.PREFERENCE,previousSort)
            }.commit()
            folder.deleteRecursively()
        }
    }
}
