package com.cruisetune.player

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
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
import com.cruisetune.player.ui.TrackAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real player and queue UI with synthetic cloud responses in the isolated build. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class DeletedQuarkTrackDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as CruiseApplication
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var passed = false
            main { passed = condition() }
            if (passed) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
    private fun capture(name: String) {
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun confirmedMissingSkipsAndMarksWhileOtherFailuresAndCompleteCacheDoNot() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val source = MusicSource("deleted-quark-device", SourceKind.QUARK, "Synthetic cloud", "test-only", "synthetic")
        val samples = 8000 * 60
        val audio = File(app.cacheDir, "deleted-quark-fixture.wav").apply {
            writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(samples * 2)
            }.array())
        }
        val tracks = listOf("失效歌曲", "正常歌曲", "网络异常歌曲").mapIndexed { index, title ->
            Track("deleted-quark-$index", source.id, "fid-$index", title,
                relativePath="0${index + 1}.wav", size=audio.length(), mimeType="audio/wav", durationMs=60000)
        }
        val goodBecomesMissing = AtomicBoolean(false)
        val goodResolutions = AtomicInteger()
        val provider = object : MusicProvider {
            override suspend fun listChildren(parentId: String) = emptyList<RemoteEntry>()
            override suspend fun resolve(fileId: String): ReadRequest = when (fileId) {
                "fid-0" -> throw ConfirmedRemoteFileMissing()
                "fid-1" -> {
                    goodResolutions.incrementAndGet()
                    if (goodBecomesMissing.get()) throw ConfirmedRemoteFileMissing()
                    ReadRequest(audio.toURI().toString(), emptyMap())
                }
                else -> throw UserError("模拟网络读取失败", retryable = false)
            }
        }
        val factoryField = LibraryRepository::class.java.getDeclaredField("providerFactory").apply { isAccessible = true }
        val previousFactory = factoryField.get(app.library)
        val previousPrefetch = app.preferences.getBoolean("prefetchNextTracks", true)
        var activity: MainActivity? = null
        var controller: MediaController? = null
        try {
            app.preferences.edit().putBoolean("prefetchNextTracks", false).commit()
            app.database.saveSource(source)
            app.database.replaceScan(source.id, tracks)
            factoryField.set(app.library, { _: SourceKind, _: String -> provider })
            val screen = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            runBlocking { app.library.reload() }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val c = future.get(10, TimeUnit.SECONDS); controller = c
            main { c.setAudioAttributes(c.audioAttributes, false); c.repeatMode = Player.REPEAT_MODE_ALL }
            fun play(track: Track) {
                lateinit var result: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                main {
                    result = c.sendCustomCommand(SessionCommand(PlaybackService.PLAY_TRACK, Bundle.EMPTY), Bundle().apply {
                        putString("trackId", track.id); putString("sourceId", source.id)
                    })
                }
                assertEquals(0, result.get(10, TimeUnit.SECONDS).resultCode)
            }

            play(tracks[0])
            await("Confirmed missing file should advance to the next real queue item") {
                c.currentMediaItem?.mediaId == tracks[1].id && c.isPlaying &&
                    c.sessionExtras.getStringArrayList("missingTrackIds")?.contains(tracks[0].id) == true
            }
            main {
                assertEquals(tracks.map { it.id }, (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
                assertEquals(Player.REPEAT_MODE_ALL, c.repeatMode)
                (MainActivity::class.java.getDeclaredField("queueTab").apply { isAccessible = true }.get(screen) as View).performClick()
            }
            await("Queue row should show the missing marker") {
                val list = screen.findViewById<RecyclerView>(R.id.player_list)
                val holder = list.findViewHolderForAdapterPosition(0) as? TrackAdapter.Holder
                holder?.title?.text?.toString() == "云端失效 · 失效歌曲" &&
                    holder.number.text.toString() == "!" &&
                    holder.row.contentDescription.toString().contains("云端文件已失效")
            }
            capture("deleted-quark-skip.png")

            main { c.pause() }
            play(tracks[2])
            await("Unconfirmed error should stay on its song") {
                c.currentMediaItem?.mediaId == tracks[2].id && c.playerError != null
            }
            SystemClock.sleep(1000)
            main {
                assertEquals(tracks[2].id, c.currentMediaItem?.mediaId)
                assertEquals(tracks.map { it.id }, (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId })
                assertTrue(c.sessionExtras.getStringArrayList("missingTrackIds").isNullOrEmpty())
                c.pause()
            }

            // Store the whole synthetic remote file, then make its provider report it missing.
            CacheWriter(app.media.streamFactory.createDataSource(), DataSpec.Builder()
                .setUri("cruisetune://track/${tracks[1].id}").setKey(tracks[1].cacheKey)
                .setLength(audio.length()).build(), ByteArray(64 * 1024), null).cache()
            assertTrue(app.media.hasCompleteStreamingCache(tracks[1]))
            goodBecomesMissing.set(true)
            val requestsBeforeCachedPlayback = goodResolutions.get()
            play(tracks[1])
            await("Complete cache should play despite cloud deletion") {
                c.currentMediaItem?.mediaId == tracks[1].id && c.isPlaying && c.currentPosition >= 1500
            }
            main {
                assertNull(c.playerError)
                assertEquals(requestsBeforeCachedPlayback, goodResolutions.get())
                assertTrue(c.sessionExtras.getStringArrayList("missingTrackIds").isNullOrEmpty())
                c.pause()
            }
            capture("deleted-quark-cached.png")
        } finally {
            main { controller?.let { it.pause(); it.setAudioAttributes(it.audioAttributes, true); it.release() }; activity?.finish() }
            runBlocking { app.library.removeSources(setOf(source.id), PlaybackSnapshot()) }
            factoryField.set(app.library, previousFactory)
            app.preferences.edit().putBoolean("prefetchNextTracks", previousPrefetch).commit()
            tracks.forEach { app.media.stream.removeResource(it.cacheKey) }
            audio.delete()
        }
    }
}
