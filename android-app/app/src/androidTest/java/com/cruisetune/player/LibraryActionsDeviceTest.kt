package com.cruisetune.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.Player
import androidx.media3.session.MediaController
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Synthetic fixtures only; never run against the user's normal application data. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class LibraryActionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as CruiseApplication
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            onMain { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail(description)
    }
    private fun click(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { (it.text?.toString() == text || it.contentDescription?.toString() == text) && it.isClickable }
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50)
        }
        fail("Visible action missing: $text")
    }

    @Test fun sortingAndRemovalKeepPlaybackAndOriginalFiles() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated authCheck build" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val source = MusicSource("device-sort-fixture", SourceKind.LOCAL, "排序测试目录", "test-only")
        val other = MusicSource("device-other-fixture", SourceKind.LOCAL, "保留测试目录", "test-only-other")
        val file = File(app.filesDir, "device-sort-fixture.wav")
        val samples = 8000 * 60
        file.writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array())
        val tracks = listOf(10, 2, 1).map { number ->
            Track("device-song-$number", source.id, "$number", "Song $number",
                relativePath="Song $number.wav", localUri=file.toURI().toString(), mimeType="audio/wav", durationMs=60000)
        }
        app.database.saveSource(source); app.database.replaceScan(source.id, tracks)
        app.database.saveSource(other)
        val retained = tracks[0].copy(id="device-retained",sourceId=other.id)
        app.database.replaceScan(other.id, listOf(retained))
        app.preferences.edit().putString(TrackSort.PREFERENCE, TrackSort.TITLE_ASC.name).commit()
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var controller: MediaController? = null
        try {
            // Exercise the same library-state notification that crashed Toast inflation on API 25.
            runBlocking { app.library.reload("已更新 3 首音乐") }
            instrumentation.waitForIdleSync()
            assertFalse(activity.isDestroyed)
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            onMain { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val c = future.get(10, TimeUnit.SECONDS); controller = c
            fun command(action: String, args: Bundle = Bundle.EMPTY) {
                lateinit var result: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                onMain { result = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
                assertEquals(0, result.get(10, TimeUnit.SECONDS).resultCode)
            }
            click("排序"); click("歌曲名称降序")
            onMain { activity.findViewById<android.view.View>(R.id.player_play).performClick() }
            await("An empty queue should start at the first displayed track") { c.currentMediaItem?.mediaId == "device-song-10" && c.playbackState == Player.STATE_READY }
            click("曲库"); click("排序"); click("歌曲名称升序")
            onMain { assertEquals("TITLE_DESC", c.sessionExtras.getString("queueSort")); c.pause() }
            click("队列"); click("排序")
            val selected = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("歌曲名称降序").single()
            assertTrue("Queue selection must reflect the queue, not the library preference", selected.isChecked)
            click("完成")
            command(PlaybackService.PLAY_TRACK, Bundle().apply { putString("trackId", tracks[1].id); putString("sourceId", source.id) })
            await("Local audio should become ready") { c.playbackState == Player.STATE_READY }
            onMain {
                assertEquals(listOf("device-song-1", "device-song-2", "device-song-10"), (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
                c.pause(); c.seekTo(12000)
            }
            await("Seek should finish") { c.currentPosition == 12000L && c.playbackState == Player.STATE_READY }
            click("队列"); click("排序"); click("歌曲名称降序")
            await("Queue should be sorted descending") { c.getMediaItemAt(0).mediaId == "device-song-10" }
            assertEquals("TITLE_ASC",app.preferences.getString(TrackSort.PREFERENCE,null))
            onMain {
                assertEquals("device-song-2",c.currentMediaItem!!.mediaId)
                assertEquals(12000L,c.currentPosition); assertFalse(c.playWhenReady)
                c.play()
            }
            await("Silent fixture should be playing") { c.isPlaying }
            command(PlaybackService.SORT_QUEUE, Bundle().apply { putString("sort", TrackSort.TITLE_ASC.name) })
            onMain { assertEquals("device-song-2",c.currentMediaItem!!.mediaId); assertTrue(c.currentPosition >= 12000); assertTrue(c.playWhenReady); c.pause() }
            await("Sorted queue should be saved") { app.database.restore().entries.firstOrNull()?.track?.id == "device-song-1" }
            click("来源"); click("移除目录：排序测试目录"); click("取消")
            assertNotNull(app.database.sources().find { it.id == source.id })
            click("来源"); click("移除目录：排序测试目录"); click("移除")
            await("Removed source must leave library and queue") { app.library.state.value.sources.none { it.id == source.id } && c.mediaItemCount == 0 }
            assertEquals(listOf(retained),app.database.tracks(other.id))
            assertTrue("Original audio must remain on disk",file.exists())
            assertTrue(app.database.restore().entries.isEmpty())
        } finally {
            onMain { controller?.release(); activity.finish() }
            runBlocking { app.library.removeSources(setOf(source.id, other.id), PlaybackSnapshot()) }
            file.delete()
        }
    }
}
