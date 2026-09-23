package com.cruisetune.player.playback

import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class PlaybackRecoveryServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    @Before fun grantSamePackageReceiverPermission() {
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private fun await(message: String, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("$message ${diagnostic()}")
    }
    private fun seed(): List<Track> {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val source = MusicSource("recovery-test", SourceKind.LOCAL, "Synthetic broken files", "test")
        val file = java.io.File(app.cacheDir, "recovery-broken.audio").apply { writeBytes(ByteArray(64) { 7 }) }
        val tracks = (0..2).map { Track("broken-$it", source.id, "file-$it", "Broken $it", size = file.length(),
            localUri = file.toURI().toString(), mimeType = "audio/flac") }
        app.database.saveSource(source); app.database.replaceScan(source.id, tracks)
        app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { i, track -> QueueEntry(track, i) }))
        return tracks
    }
    @Test fun brokenLocalFilesAreSkippedWithoutReorderingAndRepeatAllEventuallyStops() {
        exerciseBrokenQueue(Player.REPEAT_MODE_ALL)
    }
    @Test fun aBrokenSingleRepeatItemAdvancesWithoutChangingTheUsersRepeatMode() {
        exerciseBrokenQueue(Player.REPEAT_MODE_ONE)
    }
    private fun exerciseBrokenQueue(repeatMode: Int) {
        val tracks = seed()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val player = ReflectionHelpers.getField<ExoPlayer>(service.get(), "player")
        try {
            await("Queue must restore") { player.mediaItemCount == tracks.size }
            player.setAudioAttributes(player.audioAttributes, false)
            player.repeatMode = repeatMode
            val selected = mutableListOf(tracks[0].id)
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { mediaItem?.mediaId?.let(selected::add) }
            })
            player.prepare(); player.play()
            await("All broken songs must stop after one queue traversal", diagnostic = {
                "index=${player.currentMediaItemIndex}, state=${player.playbackState}, intent=${player.playWhenReady}, error=${player.playerError?.cause}, selected=$selected"
            }) {
                player.currentMediaItemIndex == 2 && !player.playWhenReady && player.playerError != null
            }
            assertEquals(tracks.map { it.id }, selected)
            assertEquals(tracks.map { it.id }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals(repeatMode, player.repeatMode)
            assertEquals(3, app.database.tracks(null).size)
        } finally { service.destroy() }
    }
    @Test fun pauseBeforeTheQueuedFailureHandlerPreventsAutomaticSkippingOrResume() {
        seed()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val player = ReflectionHelpers.getField<ExoPlayer>(service.get(), "player")
        try {
            await("Queue must restore") { player.mediaItemCount == 3 }
            player.play()
            val error = ExoPlaybackException.createForSource(EOFException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            val field = player.javaClass.getDeclaredField("playbackInfo").apply { isAccessible = true }
            val info = field.get(player)
            val copy = info.javaClass.getDeclaredMethod("copyWithPlaybackError", ExoPlaybackException::class.java).apply { isAccessible = true }
            field.set(player, copy.invoke(info, error))
            PlaybackService::class.java.getDeclaredMethod("handlePlaybackFailure", PlaybackException::class.java)
                .apply { isAccessible = true }.invoke(service.get(), error)
            player.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
            assertEquals(Player.STATE_IDLE, player.playbackState)
        } finally { service.destroy() }
    }
    @Test fun confirmedMissingCloudFileSkipsWithoutRemovingItFromTheQueue() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        app.preferences.edit().putBoolean("prefetchNextTracks", false).commit()
        val audio = ByteBuffer.allocate(44 + 8000 * 2 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
        val source = MusicSource("missing-cloud-test", SourceKind.QUARK, "Synthetic", "root")
        val tracks = (0..1).map { Track("cloud-$it", source.id, "fid-$it", "Cloud $it", size = audio.size.toLong(), mimeType = "audio/wav") }
        app.database.saveSource(source); app.database.replaceScan(source.id, tracks)
        app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { index, track -> QueueEntry(track, index) }))
        val cached = CacheDataSource.Factory().setCache(app.media.stream)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(audio) }.createDataSource()
        CacheWriter(cached, DataSpec.Builder().setUri("cruisetune://track/${tracks[1].id}")
            .setKey(tracks[1].cacheKey).build(), null, null).cache()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val player = ReflectionHelpers.getField<ExoPlayer>(service.get(), "player")
        try {
            await("Queue must restore") { player.mediaItemCount == tracks.size }
            player.setAudioAttributes(player.audioAttributes, false)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.play()
            await("Player commands must settle") { ReflectionHelpers.getField<Int>(player, "pendingOperationAcks") == 0 }
            val error = ExoPlaybackException.createForSource(ConfirmedRemoteFileMissing(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            val field = player.javaClass.getDeclaredField("playbackInfo").apply { isAccessible = true }
            val info = field.get(player)
            val copy = info.javaClass.getDeclaredMethod("copyWithPlaybackError", ExoPlaybackException::class.java).apply { isAccessible = true }
            field.set(player, copy.invoke(info, error))
            PlaybackService::class.java.getDeclaredMethod("handlePlaybackFailure", PlaybackException::class.java)
                .apply { isAccessible = true }.invoke(service.get(), error)
            await("Confirmed missing file should advance to the cached next song") { player.currentMediaItemIndex == 1 && player.isPlaying }
            assertEquals(tracks.map { it.id }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            val session = ReflectionHelpers.getField<MediaLibrarySession>(service.get(), "session")
            assertEquals(listOf(tracks[0].id), session.sessionExtras.getStringArrayList("missingTrackIds"))
        } finally { service.destroy() }
    }
    @Test fun pausingBeforeTheConfirmedMissingHandlerDoesNotSkip() {
        seed()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val player = ReflectionHelpers.getField<ExoPlayer>(service.get(), "player")
        try {
            await("Queue must restore") { player.mediaItemCount == 3 }
            player.play()
            val error = ExoPlaybackException.createForSource(ConfirmedRemoteFileMissing(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            val field = player.javaClass.getDeclaredField("playbackInfo").apply { isAccessible = true }
            val info = field.get(player)
            val copy = info.javaClass.getDeclaredMethod("copyWithPlaybackError", ExoPlaybackException::class.java).apply { isAccessible = true }
            field.set(player, copy.invoke(info, error))
            PlaybackService::class.java.getDeclaredMethod("handlePlaybackFailure", PlaybackException::class.java)
                .apply { isAccessible = true }.invoke(service.get(), error)
            player.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
            val session = ReflectionHelpers.getField<MediaLibrarySession>(service.get(), "session")
            assertNull(session.sessionExtras.getStringArrayList("missingTrackIds"))
        } finally { service.destroy() }
    }
}
