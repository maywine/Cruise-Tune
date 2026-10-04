package com.cruisetune.player.playback

import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.PlaybackSnapshot
import com.cruisetune.player.core.QueueEntry
import com.cruisetune.player.core.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class PlaybackPrefetchGateTest {
    private fun pcm(rate: Int, encoding: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(rate).setChannelCount(2).setPcmEncoding(encoding).build()

    private fun gate(bufferMs: Long, format: Format? = null, local: Boolean = false, cached: Boolean = false,
        enabled: Boolean = true, playing: Boolean = true, screenOn: Boolean = true, space: Boolean = true,
        failed: Boolean = false, bypassed: Boolean = false): Boolean {
        val app = RuntimeEnvironment.getApplication() as CruiseApplication
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(screenOn)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        app.preferences.edit().putBoolean("prefetchNextTracks", enabled).commit()
        val current = Track("current", "source", "current-file", "Current", size = 8192,
            localUri = if (local) "file:///synthetic/current.flac" else "")
        val next = Track("next", "source", "next-file", "Next", size = 8192)
        app.database.saveQueue(PlaybackSnapshot(1, listOf(QueueEntry(current, 0), QueueEntry(next, 1))))
        if (cached) {
            val source = CacheDataSource.Factory().setCache(app.media.stream)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(ByteArray(current.size.toInt())) }.createDataSource()
            CacheWriter(source, DataSpec.Builder().setUri("cruisetune://track/${current.id}")
                .setKey(current.cacheKey).setLength(current.size).build(), null, null).cache()
        }
        val bypass = if (bypassed) app.media.beginBypass(current) else null
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        val service = owner.get()
        val original = ReflectionHelpers.getField<ExoPlayer>(service, "player")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prefetch = LookAheadPrefetch(scope, { false }, { object : PrefetchAttempt {
            override suspend fun cache(): Unit = awaitCancellation()
            override fun cancel() = Unit
        } }, { 0L })
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (original.mediaItemCount != 2 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(5)
            }
            assertEquals(2, original.mediaItemCount)
            if (!space) ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 262144, 262144)
            val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, arguments ->
                when (method.name) {
                    "getAudioFormat" -> format
                    "getTotalBufferedDuration" -> bufferMs
                    "getPlayWhenReady" -> playing
                    "getPlayerError" -> if (failed) ExoPlaybackException.createForSource(IOException("Synthetic failure"),
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) else null
                    else -> method.invoke(original, *(arguments ?: emptyArray()))
                }
            } as ExoPlayer
            ReflectionHelpers.setField(service, "player", player)
            ReflectionHelpers.setField(service, "prefetch\$delegate", lazy { prefetch })
            ReflectionHelpers.callInstanceMethod<Unit>(service, "maybePrefetch")
            return ReflectionHelpers.getField(prefetch, "allowed")
        } finally {
            prefetch.cancel(); scope.cancel()
            bypass?.let(app.media::endBypass)
            ReflectionHelpers.setField(service, "player", original)
            owner.destroy()
        }
    }

    @Test fun highResolutionPcmCanPrefetchWithinItsExistingByteBudget() {
        assertTrue(gate(10_000, pcm(192000, C.ENCODING_PCM_24BIT)))
    }
    @Test fun highResolutionPcmStillWaitsForAUsefulPlaybackBuffer() {
        assertFalse(gate(1_000, pcm(192000, C.ENCODING_PCM_24BIT)))
    }
    @Test fun ordinaryPcmKeepsItsExistingBufferProtection() {
        assertFalse(gate(8_000, pcm(44100, C.ENCODING_PCM_16BIT)))
    }
    @Test fun ordinaryPcmStillPrefetchesWithEnoughBuffer() {
        assertTrue(gate(15_000, pcm(44100, C.ENCODING_PCM_16BIT)))
    }
    @Test fun highResolutionFloatAlsoUsesItsPcmByteRate() {
        assertTrue(gate(7_000, pcm(192000, C.ENCODING_PCM_FLOAT)))
    }
    @Test fun unknownFormatKeepsItsExistingBufferProtection() {
        assertFalse(gate(8_000))
    }
    @Test fun localPlaybackDoesNotRequireANetworkBuffer() { assertTrue(gate(1_000, local = true)) }
    @Test fun completeStreamCacheDoesNotRequireANetworkBuffer() { assertTrue(gate(1_000, cached = true)) }
    @Test fun completeCacheDoesNotBypassDisabledPrefetch() { assertFalse(gate(1_000, cached = true, enabled = false)) }
    @Test fun completeCacheDoesNotBypassPause() { assertFalse(gate(1_000, cached = true, playing = false)) }
    @Test fun completeCacheDoesNotBypassScreenOff() { assertFalse(gate(1_000, cached = true, screenOn = false)) }
    @Test fun completeCacheDoesNotBypassDiskReserve() { assertFalse(gate(1_000, cached = true, space = false)) }
    @Test fun completeCacheDoesNotBypassPlaybackFailure() { assertFalse(gate(1_000, cached = true, failed = true)) }
    @Test fun bypassedCacheStillRequiresPlaybackBuffer() { assertFalse(gate(1_000, cached = true, bypassed = true)) }
    @Test fun bypassedCacheCanPrefetchOnceThePlaybackBufferIsReady() { assertTrue(gate(16_000, cached = true, bypassed = true)) }
}
