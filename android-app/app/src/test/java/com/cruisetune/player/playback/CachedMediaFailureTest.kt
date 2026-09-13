package com.cruisetune.player.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.*
import androidx.media3.extractor.flac.FlacExtractor
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
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
class CachedMediaFailureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fullyCachedButTruncatedFlacFailsWithoutRetryingOrDeletingTheCache() {
        // A valid STREAMINFO block declares that more metadata follows, but the file ends here.
        val bytes = ByteArray(42)
        "fLaC".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[7] = 34; bytes[8] = 0x10; bytes[10] = 0x10
        ByteBuffer.wrap(bytes).putLong(18, (44100L shl 44) or (1L shl 41) or (15L shl 36) or 44100L)
        val spec = DataSpec.Builder().setUri("cruisetune://track/truncated").setKey("truncated").build()
        @Suppress("DEPRECATION") val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor())
        val reader = CacheDataSource.Factory().setCache(cache).createDataSource()
        val extractor = FlacExtractor()
        try {
            val writerSource = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }.createDataSource()
            CacheWriter(writerSource, spec, null, null).cache()
            assertTrue(cache.isCached("truncated", 0, bytes.size.toLong()))
            val input = DefaultExtractorInput(reader, 0, reader.open(spec))
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int) = DiscardingTrackOutput()
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) {}
            })
            val error = runCatching { repeat(10) { extractor.read(input, PositionHolder()) } }.exceptionOrNull()
            assertTrue("The real FLAC extractor must report a truncated file", error is EOFException)
            val info = LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(1, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), error as IOException, 1)
            assertEquals(C.TIME_UNSET, PersistentNetworkLoadPolicy().getRetryDelayMsFor(info))
            assertTrue("Error handling must not delete cached user audio", cache.isCached("truncated", 0, bytes.size.toLong()))
        } finally {
            extractor.release(); reader.close(); cache.release()
        }
    }
}
