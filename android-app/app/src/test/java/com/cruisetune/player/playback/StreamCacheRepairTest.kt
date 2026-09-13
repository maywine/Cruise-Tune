package com.cruisetune.player.playback

import android.app.Application
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import com.cruisetune.player.core.Track
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@androidx.media3.common.util.UnstableApi
class StreamCacheRepairTest {
    @get:Rule val temporary = TemporaryFolder()
    private val track = Track("a", "source", "a", "Synthetic", size = 8192)
    private val old = ByteArray(8192) { 1 }
    private val fresh = ByteArray(8192) { (it % 239).toByte() }
    private fun seed(cache: Cache, key: String, data: ByteArray) {
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { ByteArrayDataSource(data) }.createDataSource()
        CacheWriter(source, DataSpec.Builder().setUri("https://example.test/audio").setKey(key).build(), null, null).cache()
    }
    private fun read(cache: Cache, key: String): ByteArray {
        val source = CacheDataSource.Factory().setCache(cache).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("https://example.test/audio").setKey(key).build())
            val output = ByteArrayOutputStream(); val bytes = ByteArray(2048)
            while (true) { val n = source.read(bytes, 0, bytes.size); if (n < 0) break; output.write(bytes, 0, n) }
            return output.toByteArray()
        } finally { source.close() }
    }
    @Test fun successfulReplacementChangesOnlyTheTargetStreamingResource() = runBlocking {
        @Suppress("DEPRECATION") val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor())
        @Suppress("DEPRECATION") val offline = SimpleCache(temporary.newFolder(), NoOpCacheEvictor())
        val staging = temporary.newFolder()
        try {
            seed(cache, track.cacheKey, old); seed(cache, "other", old); seed(offline, track.cacheKey, old)
            val repair = StreamCacheRepair(cache, DataSource.Factory {
                assertArrayEquals("Old bytes survive until the new copy is obtained", old, read(cache, track.cacheKey))
                ByteArrayDataSource(fresh)
            }, staging, 16384, { true })
            repair.repair(track) { true }
            assertArrayEquals(fresh, read(cache, track.cacheKey))
            assertArrayEquals(old, read(cache, "other"))
            assertArrayEquals(old, read(offline, track.cacheKey))
            assertEquals(0, staging.listFiles()!!.size)
        } finally { cache.release(); offline.release() }
    }
    @Test fun cancelledChangedIncompleteAndOversizedCopiesNeverDiscardTheOriginal() = runBlocking {
        for (case in listOf("cancelled", "changed", "incomplete", "oversized", "no-space")) {
            @Suppress("DEPRECATION") val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor())
            val staging = temporary.newFolder(); val active = AtomicBoolean(true)
            try {
                seed(cache, track.cacheKey, old)
                val repair = StreamCacheRepair(cache, DataSource.Factory {
                    if (case == "cancelled") active.set(false)
                    ByteArrayDataSource(if (case == "incomplete") fresh.copyOf(4096) else fresh)
                }, staging, if (case == "oversized") 4096 else 16384, { case != "no-space" })
                assertTrue(case, runCatching { repair.repair(track, versionCurrent = { case != "changed" }) { active.get() } }.isFailure)
                assertArrayEquals(case, old, read(cache, track.cacheKey))
                assertEquals(0, staging.listFiles()!!.size)
            } finally { cache.release() }
        }
    }
}
