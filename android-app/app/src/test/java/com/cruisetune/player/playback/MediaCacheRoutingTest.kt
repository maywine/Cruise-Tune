package com.cruisetune.player.playback

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.Track
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class MediaCacheRoutingTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    private val track = Track("a", "source", "a", "Synthetic", size = 8)
    @Before fun grantSamePackageReceiverPermission() {
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    private fun media(): MediaCache {
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        return app.media
    }
    private fun seed(cache: Cache, value: Byte) {
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { ByteArrayDataSource(ByteArray(8) { value }) }.createDataSource()
        CacheWriter(source, DataSpec.Builder().setUri("cruisetune://track/a").setKey(track.cacheKey).build(), null, null).cache()
    }
    @Test fun eligibilityRequiresAStreamingReadAndDoesNotTreatOfflineBytesAsRepairableStreaming() {
        val media = media()
        seed(media.stream, 1)
        assertFalse(media.canRepairStreaming(track))
        fun read(): Byte {
            val source = media.playbackFactory.createDataSource()
            val bytes = ByteArray(8)
            try {
                source.open(DataSpec.Builder().setUri("cruisetune://track/a").setKey(track.cacheKey).build())
                assertEquals(8, source.read(bytes, 0, bytes.size))
            } finally { source.close() }
            return bytes[0]
        }
        assertEquals(1.toByte(), read())
        assertTrue(media.canRepairStreaming(track))
        assertFalse(media.canRepairStreaming(track.copy(localUri = "content://local/a")))
        seed(media.offline, 2)
        assertEquals(2.toByte(), read())
        assertFalse(media.canRepairStreaming(track))
    }
    @Test fun cancellingAnOldAttemptCannotDisableANewerBypassForTheSameSong() {
        val media = media()
        val first = media.beginBypass(track)
        val second = media.beginBypass(track)
        media.endBypass(first)
        assertTrue(media.isBypassed(track.cacheKey))
        assertFalse(media.isBypassed("unrelated"))
        media.endBypass(second)
        assertFalse(media.isBypassed(track.cacheKey))
    }
}
