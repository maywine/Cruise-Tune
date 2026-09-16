package com.cruisetune.player.playback

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.Handler
import android.os.PowerManager
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import java.io.EOFException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class RecoveryNetworkTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    @Before fun grantSamePackageReceiverPermission() {
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private val audio = ByteBuffer.allocate(44 + 8000 * 2 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(capacity() - 44)
    }.array()

    private fun await(message: String, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("$message ${diagnostic()}")
    }

    private fun seed(): List<Track> {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        app.preferences.edit().putBoolean("prefetchNextTracks", false).commit()
        val source = MusicSource("network-recovery", SourceKind.QUARK, "Synthetic", "root")
        val tracks = (0..1).map { Track("network-$it", source.id, "file-$it", "Synthetic $it",
            size = audio.size.toLong(), mimeType = "audio/wav") }
        app.database.saveSource(source); app.database.replaceScan(source.id, tracks)
        app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { i, track -> QueueEntry(track, i) }))
        return tracks
    }

    private fun factory(opens: AtomicInteger, failures: Int = 0) = DataSource.Factory {
        val bytes = ByteArrayDataSource(audio)
        object : DataSource by bytes {
            override fun open(dataSpec: DataSpec): Long {
                if (opens.incrementAndGet() <= failures) throw transportFailure(dataSpec)
                return bytes.open(dataSpec)
            }
        }
    }

    private fun transportFailure(spec: DataSpec = DataSpec.Builder().setUri("https://example.test/audio").build()) =
        HttpDataSource.HttpDataSourceException(SocketTimeoutException(), spec,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ)

    private fun player(service: PlaybackService) = ReflectionHelpers.getField<ExoPlayer>(service, "player")
    private fun recovery(service: PlaybackService) = ReflectionHelpers.getField<Any?>(service, "recovery")
    private fun action(service: PlaybackService) = ReflectionHelpers.getField<Job?>(service, "recoveryAction")
    private fun invoke(service: PlaybackService, name: String) =
        PlaybackService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)

    private fun injectRecovery(service: PlaybackService, track: Track) {
        // A late seek/play acknowledgement contains the real engine state and would overwrite
        // the synthetic error. Drain those commands before injecting an otherwise terminal state.
        await("Player commands must settle before injecting recovery") {
            ReflectionHelpers.getField<Int>(player(service), "pendingOperationAcks") == 0
        }
        val c = Class.forName("com.cruisetune.player.playback.PlaybackService\$Recovery")
            .getDeclaredConstructor(Track::class.java, MediaCache.Bypass::class.java,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        ReflectionHelpers.setField(service, "recovery", c.newInstance(track, app.media.beginBypass(track),
            player(service).currentPosition, false, 0))
    }

    private fun report(service: PlaybackService, cause: java.io.IOException) {
        val player = player(service)
        val error = ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val field = player.javaClass.getDeclaredField("playbackInfo").apply { isAccessible = true }
        val info = field.get(player)
        val copy = info.javaClass.getDeclaredMethod("copyWithPlaybackError", ExoPlaybackException::class.java).apply { isAccessible = true }
        field.set(player, copy.invoke(info, error))
        PlaybackService::class.java.getDeclaredMethod("handlePlaybackFailure", PlaybackException::class.java)
            .apply { isAccessible = true }.invoke(service, error)
    }

    @Test fun actualCachedParserFailureRecoversAfterTransientNetworkFailuresEvenWhenInitiallyOffline() {
        val tracks = seed(); val opens = AtomicInteger()
        ReflectionHelpers.setField(app.media, "network", factory(opens, failures = 2))
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        // A FLAC metadata block declares another block, but the cached bytes stop here.
        val corrupt = ByteArray(42).apply {
            "fLaC".toByteArray().copyInto(this); this[7] = 34; this[8] = 0x10; this[10] = 0x10
            ByteBuffer.wrap(this).putLong(18, (44100L shl 44) or (1L shl 41) or (15L shl 36) or 44100L)
        }
        val writer = CacheDataSource.Factory().setCache(app.media.stream)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(corrupt) }.createDataSource()
        CacheWriter(writer, DataSpec.Builder().setUri("cruisetune://track/${tracks[0].id}").setKey(tracks[0].cacheKey).build(), null, null).cache()
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        val player = player(owner.get())
        try {
            await("Queue must restore") { player.mediaItemCount == 2 }
            player.setAudioAttributes(player.audioAttributes, false)
            player.prepare(); player.play()
            await("Recovery must retain the load across timeouts and eventually decode fresh audio") {
                opens.get() >= 3 && player.playbackState == Player.STATE_READY && player.playerError == null
            }
            assertEquals(tracks[0].id, player.currentMediaItem?.mediaId)
            assertEquals(tracks.map { it.id }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertTrue(player.playWhenReady)
        } finally { owner.destroy() }
    }

    @Test fun terminalTransientErrorSchedulesAndExecutesReloadWithoutChangingTrackOrPosition() {
        val tracks = seed(); val opens = AtomicInteger()
        ReflectionHelpers.setField(app.media, "network", factory(opens))
        val owner = Robolectric.buildService(PlaybackService::class.java).create(); val service = owner.get(); val player = player(service)
        val playbackGate = CountDownLatch(1)
        try {
            await("Queue must restore") { player.mediaItemCount == 2 }
            player.setAudioAttributes(player.audioAttributes, false)
            val entered = CountDownLatch(1)
            Handler(player.playbackLooper).post { entered.countDown(); playbackGate.await() }
            assertTrue("Playback thread must reach the command barrier", entered.await(2, TimeUnit.SECONDS))
            player.seekTo(0, 1200); player.play(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ReflectionHelpers.getField<Int>(player, "pendingOperationAcks") > 0)
            Handler(Looper.getMainLooper()).post { playbackGate.countDown() }
            injectRecovery(service, tracks[0])
            assertEquals(0, ReflectionHelpers.getField<Int>(player, "pendingOperationAcks"))
            report(service, transportFailure())
            assertTrue(action(service)?.isActive == true)
            assertTrue(app.media.isBypassed(tracks[0].cacheKey))
            await("The delayed retry must actually reopen audio", diagnostic = {
                "opens=${opens.get()}, state=${player.playbackState}, position=${player.currentPosition}, error=${player.playerError?.errorCode}"
            }) { opens.get() > 0 && player.playbackState == Player.STATE_READY }
            assertEquals(tracks[0].id, player.currentMediaItem?.mediaId)
            assertTrue(player.currentPosition >= 1200)
            assertNull(player.playerError)
        } finally { playbackGate.countDown(); owner.destroy() }
    }

    @Test fun waitingBeyondFortyFiveSecondsNeverSkipsForOfflineUnvalidatedOrValidatedNetworks() {
        val tracks = seed()
        val owner = Robolectric.buildService(PlaybackService::class.java).create(); val service = owner.get(); val player = player(service)
        try {
            await("Queue must restore") { player.mediaItemCount == 2 }
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val originalInfo = manager.activeNetworkInfo
            for (state in listOf("offline", "unvalidated", "validated")) {
                shadowOf(manager).setActiveNetworkInfo(if (state == "offline") null else originalInfo)
                manager.activeNetwork?.let { network ->
                    val caps = NetworkCapabilities()
                    shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (state == "validated") shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    shadowOf(manager).setNetworkCapabilities(network, caps)
                }
                player.seekTo(0, 1200); player.play(); shadowOf(Looper.getMainLooper()).idle()
                injectRecovery(service, tracks[0])
                // This is an outstanding load with no new file error; it must not consume a failure budget.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
                assertEquals(state, 0, player.currentMediaItemIndex)
                assertEquals(state, 1200L, player.currentPosition)
                assertTrue(state, player.playWhenReady)
                assertNotNull(state, recovery(service))
                assertTrue(state, app.media.isBypassed(tracks[0].cacheKey))
                player.pause()
            }
        } finally { owner.destroy() }
    }

    @Test fun pauseCancelsADelayedNetworkRetry() = exerciseRetryCancellation("pause")
    @Test fun seekCancelsThePreviousTracksDelayedNetworkRetry() = exerciseRetryCancellation("seek")
    @Test fun screenOffCancelsADelayedNetworkRetry() = exerciseRetryCancellation("screen-off")
    private fun exerciseRetryCancellation(stop: String) {
        val tracks = seed(); val opens = AtomicInteger()
        ReflectionHelpers.setField(app.media, "network", factory(opens))
        val owner = Robolectric.buildService(PlaybackService::class.java).create(); val service = owner.get(); val player = player(service)
        try {
            await("Queue must restore") { player.mediaItemCount == 2 }
            // Keep each case isolated: an explicit seek after the previous case's error now
            // legitimately prepares the selected item, and must not pollute a later case's counts.
            player.seekTo(0, 1200); player.play(); shadowOf(Looper.getMainLooper()).idle()
            injectRecovery(service, tracks[0]); report(service, transportFailure())
            assertTrue(action(service)?.isActive == true)
            when (stop) {
                "pause" -> player.pause()
                "seek" -> player.seekTo(1, 0)
                else -> invoke(service, "pauseForScreenOff")
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35))
            assertFalse(stop, action(service)?.isActive == true)
            assertNull(stop, recovery(service))
            assertFalse(stop, app.media.isBypassed(tracks[0].cacheKey))
            assertEquals(stop, 0, opens.get())
            if (stop != "seek") assertFalse(player.playWhenReady)
        } finally { owner.destroy() }
    }

    @Test fun authorizationAndUnknownErrorsDuringRecoveryDoNotSkipOrRetryAsBrokenFiles() {
        val tracks = seed()
        val owner = Robolectric.buildService(PlaybackService::class.java).create(); val service = owner.get(); val player = player(service)
        try {
            await("Queue must restore") { player.mediaItemCount == 2 }
            for (error in listOf(UserError("Login required", needsLogin = true), java.io.IOException("Unclassified"))) {
                player.play(); shadowOf(Looper.getMainLooper()).idle(); injectRecovery(service, tracks[0])
                report(service, error)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35))
                assertEquals(0, player.currentMediaItemIndex)
                assertNull(recovery(service))
                assertFalse(action(service)?.isActive == true)
            }
        } finally { owner.destroy() }
    }
}
