package com.cruisetune.player.playback

import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import com.cruisetune.player.steering.SteeringAction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class CachedTrackSelectionTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    private val audio = ByteBuffer.allocate(44 + 8000 * 2 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(capacity() - 44)
    }.array()
    @Before fun permissions() {
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        app.preferences.edit().putBoolean("prefetchNextTracks", false).commit()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail(message)
    }
    private data class Fixture(val owner: ServiceController<PlaybackService>, val player: ExoPlayer,
        val tracks: List<Track>, val requests: AtomicInteger, val requestsByKey: Map<String, AtomicInteger>)
    private fun fixture(offline: Boolean = false, authError: Boolean = false): Fixture {
        val source = MusicSource("cached-selection", SourceKind.QUARK, "Synthetic", "root")
        val tracks = (0..3).map { Track("selection-$it", source.id, "file-$it", "Synthetic $it", size = audio.size.toLong(), mimeType = "audio/wav") }
        app.database.saveSource(source); app.database.replaceScan(source.id, tracks)
        app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { i, t -> QueueEntry(t, i) }))
        val media = app.media
        for (track in tracks.drop(1)) {
            val writer = CacheDataSource.Factory().setCache(if (offline) media.offline else media.stream)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(audio) }.createDataSource()
            CacheWriter(writer, DataSpec.Builder().setUri("cruisetune://track/${track.id}").setKey(track.cacheKey).build(), null, null).cache()
            assertEquals("已缓存完整", media.status(track))
        }
        val requests = AtomicInteger()
        val requestsByKey = ConcurrentHashMap<String, AtomicInteger>()
        val unreachable = DataSource.Factory { object : DataSource {
            override fun addTransferListener(listener: TransferListener) {}
            override fun open(spec: DataSpec): Long {
                requests.incrementAndGet()
                requestsByKey.getOrPut(checkNotNull(spec.key)) { AtomicInteger() }.incrementAndGet()
                throw UserError(if (authError) "夸克账号需重新连接" else "夸克暂时无法连接，请稍后重试", needsLogin = authError)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("No remote bytes")
            override fun getUri(): Uri? = null
            override fun close() {}
        } }
        media.streamFactory.setUpstreamDataSourceFactory(unreachable)
        ReflectionHelpers.setField(media, "network", unreachable)
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
        await("Queue restore") { player.mediaItemCount == 4 }
        player.setAudioAttributes(player.audioAttributes, false)
        return Fixture(owner, player, tracks, requests, requestsByKey)
    }
    private fun failFirst(f: Fixture, index: Int = 0) {
        if (index != f.player.currentMediaItemIndex) f.player.seekTo(index, 0)
        f.player.prepare(); f.player.play()
        await("The uncached first song must reach a terminal Quark error") {
            f.player.playbackState == Player.STATE_IDLE && f.player.playerError != null
        }
        assertEquals(1, f.requests.get())
    }
    private fun cachedSteps(offline: Boolean, auth: Boolean) {
        val f = fixture(offline, auth)
        try {
            failFirst(f)
            for (index in 1..3) {
                f.player.seekToNextMediaItem() // Same session command as the next button / media controls.
                await("Next cached song must prepare after a prior Quark failure; index=$index, offline=$offline") {
                    f.player.currentMediaItemIndex == index && f.player.playbackState == Player.STATE_READY && f.player.playerError == null
                }
                assertTrue(f.player.playWhenReady)
                assertEquals("No Quark calls while reading complete cache", 1, f.requests.get())
                assertEquals("已缓存完整", app.media.status(f.tracks[index]))
                val session = ReflectionHelpers.getField<androidx.media3.session.MediaLibraryService.MediaLibrarySession>(f.owner.get(), "session")
                assertFalse(session.sessionExtras.getBoolean("needsLogin"))
                assertNull(session.sessionExtras.getString("error"))
            }
        } finally { f.owner.destroy() }
    }
    @Test fun nextTracksUseStreamingCacheAfterATerminalQuarkFailure() = cachedSteps(false, false)
    private fun wheel(f: Fixture, action: SteeringAction, valid: () -> Boolean = { true }) {
        PlaybackService::class.java.getDeclaredMethod("handleSteeringAction", SteeringAction::class.java, Function0::class.java)
            .apply { isAccessible = true }.invoke(f.owner.get(), action, valid)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun steeringNextAfterQuarkFailurePlaysCompleteCacheWithoutNetwork() {
        val f = fixture(authError = true)
        try {
            failFirst(f); wheel(f, SteeringAction.NEXT)
            await("Steering Next must reuse error recovery for cached songs") { f.player.currentMediaItemIndex == 1 && f.player.playbackState == Player.STATE_READY }
            assertNull(f.player.playerError); assertTrue(f.player.playWhenReady); assertEquals(1, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun steeringNextWhilePausedKeepsPauseIntent() {
        val f = fixture()
        try {
            failFirst(f); f.player.pause(); wheel(f, SteeringAction.NEXT)
            await("Paused wheel selection should clear old error") { f.player.currentMediaItemIndex == 1 && f.player.playbackState == Player.STATE_READY }
            assertFalse(f.player.playWhenReady); assertEquals(1, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun steeringTogglePreparesPausedCompleteCacheAndThenPauses() {
        val f = fixture()
        try {
            f.player.seekTo(1, 0); wheel(f, SteeringAction.TOGGLE)
            await("Wheel Play must prepare") { f.player.playbackState == Player.STATE_READY && f.player.playWhenReady }
            wheel(f, SteeringAction.TOGGLE); assertFalse(f.player.playWhenReady); assertEquals(0, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun wheelDoesNotResumeOrChangeSelectionDuringScreenOffOrCall() {
        val f = fixture()
        try {
            f.player.seekTo(1, 0)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            wheel(f, SteeringAction.NEXT); wheel(f, SteeringAction.PLAY)
            assertEquals(1, f.player.currentMediaItemIndex); assertFalse(f.player.playWhenReady)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            app.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            wheel(f, SteeringAction.NEXT); wheel(f, SteeringAction.TOGGLE)
            assertEquals(1, f.player.currentMediaItemIndex); assertFalse(f.player.playWhenReady)
        } finally { f.owner.destroy() }
    }
    @Test fun disabledOrStaleSteeringCommandCannotChangePlayback() {
        val f = fixture()
        try {
            f.player.seekTo(1, 0); wheel(f, SteeringAction.NEXT) { false }; wheel(f, SteeringAction.PLAY) { false }
            assertEquals(1, f.player.currentMediaItemIndex); assertFalse(f.player.playWhenReady); assertEquals(0, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun nextTracksUseOfflineCacheEvenWhenTheAccountRequiresLogin() = cachedSteps(true, true)
    @Test fun cachedSelectionWhilePausedMustNotStartPlaying() {
        val f = fixture()
        try {
            failFirst(f); f.player.pause(); f.player.seekToNextMediaItem()
            await("Paused cached selection should leave the old error behind") { f.player.playbackState == Player.STATE_READY && f.player.playerError == null }
            assertFalse(f.player.playWhenReady)
            assertEquals(1, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun selectingACompleteCacheDirectlyDoesNotContactQuark() {
        val f = fixture()
        try {
            f.player.seekTo(1, 0); f.player.prepare(); f.player.play()
            await("Complete cached audio must decode") { f.player.playbackState == Player.STATE_READY }
            assertNull(f.player.playerError)
            assertEquals(0, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun previousTracksAlsoRestartFromCacheAfterAnError() {
        val f = fixture()
        try {
            app.media.stream.removeResource(f.tracks[3].cacheKey)
            failFirst(f, 3)
            for (index in 2 downTo 1) {
                f.player.seekToPreviousMediaItem()
                await("Previous cached song must prepare") { f.player.currentMediaItemIndex == index && f.player.playbackState == Player.STATE_READY }
                assertNull(f.player.playerError)
                assertTrue(f.player.playWhenReady)
                // Media3 may prepare the uncached following song before it is selected.
                // Neither selected cached song may call the upstream.
                assertNull(f.requestsByKey[f.tracks[index].cacheKey])
            }
        } finally { f.owner.destroy() }
    }
    @Test fun rapidNextSelectionsPrepareTheFinalSelectedCache() {
        val f = fixture()
        try {
            failFirst(f)
            f.player.seekToNextMediaItem(); f.player.seekToNextMediaItem()
            await("Rapid next commands must leave the final song ready") { f.player.currentMediaItemIndex == 2 && f.player.playbackState == Player.STATE_READY }
            assertNull(f.player.playerError)
            assertEquals(1, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun screenOffPreventsErrorSelectionFromPreparingOrResuming() {
        val f = fixture()
        try {
            failFirst(f)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            PlaybackService::class.java.getDeclaredMethod("pauseForScreenOff").apply { isAccessible = true }.invoke(f.owner.get())
            f.player.seekToNextMediaItem()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(f.player.playWhenReady)
            assertEquals(Player.STATE_IDLE, f.player.playbackState)
            assertEquals(1, f.requests.get())
        } finally { f.owner.destroy() }
    }
    @Test fun browsingARestoredUnstartedQueueDoesNotStartLoading() {
        val f = fixture()
        try {
            f.player.seekToNextMediaItem()
            await("The selection must settle") { ReflectionHelpers.getField<Int>(f.player, "pendingOperationAcks") == 0 }
            assertEquals(1, f.player.currentMediaItemIndex)
            assertEquals(Player.STATE_IDLE, f.player.playbackState)
            assertFalse(f.player.playWhenReady)
            assertEquals(0, f.requests.get())
        } finally { f.owner.destroy() }
    }
}
