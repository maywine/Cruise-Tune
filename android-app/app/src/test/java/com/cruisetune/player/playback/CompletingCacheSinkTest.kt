package com.cruisetune.player.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@androidx.media3.common.util.UnstableApi
class CompletingCacheSinkTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun completeRangeIsVisibleBeforeLoaderAsksForEofOrCloses() {
        val data=ByteArray(6*1024*1024+137){(it%251).toByte()}
        @Suppress("DEPRECATION") val cache=SimpleCache(temporary.newFolder(),NoOpCacheEvictor())
        val source=CacheDataSource.Factory().setCache(cache)
            .setCacheWriteDataSinkFactory { CompletingCacheSink(CacheDataSink.Factory().setCache(cache)) }
            .setUpstreamDataSourceFactory { ByteArrayDataSource(data) }.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("https://example.test/audio").setKey("track")
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build())
            val buffer=ByteArray(65536);var read=0
            while(read<data.size-1)read+=source.read(buffer,0,minOf(buffer.size,data.size-1-read))
            assertFalse(cache.isCached("track",0,data.size.toLong()))
            assertEquals(1,source.read(buffer,0,1))
            // Deliberately don't request EOF or close: this is the player's buffered-playback window.
            assertTrue(cache.isCached("track",0,data.size.toLong()))
            assertEquals(data.size.toLong(),cache.getCachedBytes("track",0,data.size.toLong()))
        } finally {source.close();cache.release()}
    }
    @Test fun committedTailDoesNotHideAnEarlierGap() {
        val data=ByteArray(300000)
        @Suppress("DEPRECATION") val cache=SimpleCache(temporary.newFolder(),NoOpCacheEvictor())
        val source=CacheDataSource.Factory().setCache(cache)
            .setCacheWriteDataSinkFactory { CompletingCacheSink(CacheDataSink.Factory().setCache(cache)) }
            .setUpstreamDataSourceFactory { ByteArrayDataSource(data) }.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("https://example.test/audio").setKey("track").setPosition(100000).build())
            val buffer=ByteArray(16384);var read=0
            while(read<200000)read+=source.read(buffer,0,minOf(buffer.size,200000-read))
            assertTrue(cache.isCached("track",100000,200000))
            assertFalse(cache.isCached("track",0,300000))
        } finally {source.close();cache.release()}
    }
    @Test fun unknownLengthStillWaitsForCloseAndCloseIsIdempotent() {
        var closes=0
        val sink=CompletingCacheSink(DataSink.Factory { object:DataSink {
            override fun open(dataSpec:DataSpec){}
            override fun write(buffer:ByteArray,offset:Int,length:Int){}
            override fun close(){closes++}
        } })
        sink.open(DataSpec.Builder().setUri("https://example.test/audio").build())
        sink.write(ByteArray(1),0,1);assertEquals(0,closes)
        sink.close();sink.close();assertEquals(1,closes)
    }
}
