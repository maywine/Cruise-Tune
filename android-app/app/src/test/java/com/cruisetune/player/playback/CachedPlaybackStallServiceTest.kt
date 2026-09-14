package com.cruisetune.player.playback

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class CachedPlaybackStallServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    @Before fun grantSamePackageReceiverPermission() {
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private data class Fixture(val service: ServiceController<PlaybackService>, val player: ExoPlayer,
        val tracks: List<Track>, val freshReads: AtomicInteger)

    private fun fixture(complete: Boolean = true, online: Boolean = true, freshReadGate: CountDownLatch? = null): Fixture {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        if (online) {
            val network = checkNotNull(connectivity.activeNetwork)
            val capabilities = NetworkCapabilities()
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
        } else shadowOf(connectivity).setActiveNetworkInfo(null)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        val source = MusicSource("cached-stall", SourceKind.QUARK, "Synthetic cached tracks", "test")
        val bytes = ByteArray(64) { 7 }
        val file = java.io.File(app.cacheDir, "cached-stall.audio").apply { writeBytes(bytes) }
        val tracks = (0..2).map { Track("cached-$it", source.id, "file-$it", "Synthetic $it",
            size = if (it == 0 && !complete) 128 else 64, mimeType = "audio/flac", localUri = if (it == 0) "" else file.toURI().toString()) }
        app.database.saveSource(source); app.database.replaceScan(source.id, tracks)
        app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { index, track -> QueueEntry(track, index) }))
        val spec = DataSpec.Builder().setUri("cruisetune://track/${tracks[0].id}").setKey(tracks[0].cacheKey).build()
        val seed = CacheDataSource.Factory().setCache(app.media.stream).setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }.createDataSource()
        CacheWriter(seed, spec, null, null).cache()
        val reader = app.media.playbackFactory.createDataSource()
        try { reader.open(spec); assertEquals(64, reader.read(ByteArray(64), 0, 64)) } finally { reader.close() }
        assertTrue(app.media.canRepairStreaming(tracks[0]))
        assertEquals(complete, app.media.hasCompleteStreamingCache(tracks[0]))
        val freshReads = AtomicInteger()
        ReflectionHelpers.setField(app.media, "network", DataSource.Factory {
            freshReads.incrementAndGet()
            val delegate = ByteArrayDataSource(bytes)
            object : DataSource by delegate {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    try { freshReadGate?.await() } catch (_: InterruptedException) { throw java.io.InterruptedIOException() }
                    return delegate.read(buffer, offset, length)
                }
            }
        })
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val player = ReflectionHelpers.getField<ExoPlayer>(service.get(), "player")
        await { player.mediaItemCount == 3 }
        player.setAudioAttributes(player.audioAttributes, false)
        player.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        return Fixture(service, player, tracks, freshReads)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Expected playback service condition")
    }
    private fun stalledTick(fixture: Fixture, state: Int) {
        // Model the engine's READY/BUFFERING reports without generating a parser error.
        val field = fixture.player.javaClass.getDeclaredField("playbackInfo").apply { isAccessible = true }
        val info = field.get(fixture.player)
        val copy = info.javaClass.getDeclaredMethod("copyWithPlaybackState", Int::class.javaPrimitiveType).apply { isAccessible = true }
        field.set(fixture.player, copy.invoke(info, state))
        PlaybackService::class.java.getDeclaredMethod("checkPlaybackStall").apply { isAccessible = true }.invoke(fixture.service.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }
    @Test fun fullCacheFlappingWithoutErrorsStartsOneFreshReadAndThenSkipsOnFailure() {
        val fixture = fixture()
        try {
            repeat(19) { stalledTick(fixture, if (it % 2 == 0) Player.STATE_READY else Player.STATE_BUFFERING) }
            assertEquals(0L, fixture.player.currentPosition)
            assertNull(fixture.player.playerError)
            assertEquals(0, fixture.freshReads.get())
            repeat(3) { if (fixture.freshReads.get() == 0) stalledTick(fixture, Player.STATE_BUFFERING) }
            await { fixture.freshReads.get() > 0 }
            await { fixture.player.currentMediaItemIndex == 2 && !fixture.player.playWhenReady }
            assertEquals("Only one bypass read is allowed for the stalled song", 1, fixture.freshReads.get())
            assertEquals(fixture.tracks.map { it.id }, (0 until fixture.player.mediaItemCount).map { fixture.player.getMediaItemAt(it).mediaId })
        } finally { fixture.service.destroy() }
    }
    @Test fun incompleteNetworkBufferingDoesNotTriggerCacheRecovery() {
        val fixture = fixture(complete = false)
        try {
            repeat(32) { stalledTick(fixture, if (it % 2 == 0) Player.STATE_READY else Player.STATE_BUFFERING) }
            assertEquals(0, fixture.freshReads.get())
            assertTrue(fixture.player.playWhenReady)
            assertEquals(0, fixture.player.currentMediaItemIndex)
        } finally { fixture.service.destroy() }
    }
    @Test fun aBypassThatAlsoMakesNoProgressKeepsWaitingWithoutSkippingOrRestarting() {
        val gate = CountDownLatch(1)
        val fixture = fixture(freshReadGate = gate)
        try {
            repeat(22) { if (fixture.freshReads.get() == 0) stalledTick(fixture, Player.STATE_BUFFERING) }
            await { fixture.freshReads.get() == 1 }
            assertNull(fixture.player.playerError)
            repeat(180) { stalledTick(fixture, if (it % 2 == 0) Player.STATE_READY else Player.STATE_BUFFERING) }
            assertEquals(0, fixture.player.currentMediaItemIndex)
            assertTrue(fixture.player.playWhenReady)
            assertTrue(app.media.isBypassed(fixture.tracks[0].cacheKey))
            assertEquals(1, fixture.freshReads.get())
            assertTrue(app.media.hasCompleteStreamingCache(fixture.tracks[0]))
        } finally { gate.countDown(); fixture.service.destroy() }
    }
    @Test fun offlineCachedStallKeepsTheRecoveryPendingWithoutDeletingCacheOrSkipping() {
        val gate = CountDownLatch(1)
        val fixture = fixture(online = false, freshReadGate = gate)
        try {
            repeat(22) { if (fixture.freshReads.get() == 0) stalledTick(fixture, Player.STATE_BUFFERING) }
            await { fixture.freshReads.get() == 1 }
            repeat(180) { stalledTick(fixture, Player.STATE_BUFFERING) }
            assertTrue(fixture.player.playWhenReady)
            assertEquals(0, fixture.player.currentMediaItemIndex)
            assertEquals(1, fixture.freshReads.get())
            assertTrue(app.media.isBypassed(fixture.tracks[0].cacheKey))
            assertTrue(app.media.hasCompleteStreamingCache(fixture.tracks[0]))
        } finally { gate.countDown(); fixture.service.destroy() }
    }
    @Test fun exhaustedStallProbeBudgetDoesNotMarkTheFileBadOrSkipIt() {
        val fixture = fixture()
        try {
            val policy = ReflectionHelpers.getField<PlaybackRecoveryPolicy>(fixture.service.get(), "recoveryPolicy")
            assertTrue(policy.tryRecovery(fixture.tracks[0].cacheKey))
            repeat(22) { if (fixture.player.playWhenReady) stalledTick(fixture, Player.STATE_BUFFERING) }
            assertFalse(fixture.player.playWhenReady)
            assertEquals(0, fixture.player.currentMediaItemIndex)
            assertEquals(0, fixture.freshReads.get())
            assertEquals(0, policy.nextIndex(fixture.tracks.map { it.cacheKey }, 2) { (it + 1) % 3 })
            assertTrue(app.media.hasCompleteStreamingCache(fixture.tracks[0]))
        } finally { fixture.service.destroy() }
    }
    @Test fun nextAndPreviousDoNotCarryTheOriginalSongsStallDeadline() {
        val fixture = fixture()
        try {
            repeat(16) { stalledTick(fixture, Player.STATE_BUFFERING) }
            fixture.player.seekTo(1, 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            fixture.player.seekTo(0, 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            repeat(16) { stalledTick(fixture, Player.STATE_BUFFERING) }
            assertEquals(0, fixture.freshReads.get())
            repeat(6) { if (fixture.freshReads.get() == 0) stalledTick(fixture, Player.STATE_BUFFERING) }
            await { fixture.freshReads.get() == 1 }
        } finally { fixture.service.destroy() }
    }
}
