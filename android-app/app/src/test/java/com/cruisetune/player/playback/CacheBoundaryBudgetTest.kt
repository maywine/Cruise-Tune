package com.cruisetune.player.playback

import androidx.media3.common.C
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,33],application=CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class CacheBoundaryBudgetTest {
    private val app get()=RuntimeEnvironment.getApplication() as CruiseApplication
    @Before fun setup(){
        shadowOf(app).grantPermissions(app.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        space(1500000)
    }
    private fun space(available:Int)=ShadowStatFs.registerStats(app.filesDir.absolutePath,2000000,available,available)
    private fun track(size:Long=8192)=Track("bounded","source","file","Synthetic",size=size).also {
        app.database.saveSource(MusicSource("source",SourceKind.QUARK,"Synthetic","root"))
        app.database.replaceScan("source",listOf(it))
    }
    private fun seed(cache:Cache,t:Track,length:Long=t.size){
        val writer=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory{ByteArrayDataSource(ByteArray(t.size.toInt()){7})}.createDataSource()
        CacheWriter(writer,DataSpec.Builder().setUri("cruisetune://track/${t.id}").setKey(t.cacheKey).setLength(length).build(),null,null).cache()
    }
    private fun forbiddenUpstream() = DataSource.Factory {
        val bytes = ByteArrayDataSource(byteArrayOf(0))
        object : DataSource by bytes {
            override fun open(spec: DataSpec): Long = error("Complete cache must not open upstream")
        }
    }
    private fun read(media:MediaCache,t:Track,position:Long=0,length:Long=C.LENGTH_UNSET.toLong()):ByteArray{
        val source=media.playbackFactory.createDataSource();val result=java.io.ByteArrayOutputStream()
        try{
            source.open(DataSpec.Builder().setUri("cruisetune://track/${t.id}").setKey(t.cacheKey).setPosition(position).setLength(length).build())
            val bytes=ByteArray(2048)
            while(true){val count=source.read(bytes,0,bytes.size);if(count<0)break;result.write(bytes,0,count)}
            assertEquals(0,source.read(bytes,0,0))
        }finally{source.close()}
        return result.toByteArray()
    }
    @Test fun completeFixedRangeCacheReachesEofWithoutNetworkIncludingTailAndExactEnd(){
        val t=track();val media=app.media;seed(media.stream,t)
        assertEquals(C.LENGTH_UNSET.toLong(),ContentMetadata.getContentLength(media.stream.getContentMetadata(t.cacheKey)))
        media.streamFactory.setUpstreamDataSourceFactory(forbiddenUpstream())
        assertEquals("已缓存完整",media.status(t))
        assertArrayEquals(ByteArray(8192){7},read(media,t))
        assertArrayEquals(ByteArray(8){7},read(media,t,8184,100))
        assertEquals(0,read(media,t,8192).size)
        assertTrue(runCatching{read(media,t,8193)}.exceptionOrNull() is DataSourceException)
        assertTrue(media.canRepairStreaming(t))
    }
    @Test fun completeOfflineCacheAndOldQueueVersionUseTheMatchingKnownLength(){
        val old=track();val media=app.media;seed(media.offline,old)
        app.database.saveQueue(PlaybackSnapshot(1,listOf(QueueEntry(old,0))))
        app.database.replaceScan(old.sourceId,listOf(old.copy(version="new",size=16384)))
        media.streamFactory.setUpstreamDataSourceFactory(forbiddenUpstream())
        assertEquals(8192,read(media,old).size)
        assertEquals(old,app.database.findTrackForCache(old.id,old.cacheKey))
    }
    @Test fun partialCacheStillFetchesItsMissingBytes(){
        val t=track();val media=app.media;seed(media.stream,t,4096)
        val calls=AtomicInteger()
        media.streamFactory.setUpstreamDataSourceFactory{calls.incrementAndGet();ByteArrayDataSource(ByteArray(8192){7})}
        assertArrayEquals(ByteArray(8192){7},read(media,t));assertEquals(1,calls.get())
        assertTrue(media.hasCompleteStreamingCache(t))
    }
    @Test fun restartDoesNotSubtractExistingCachedBytesAgain(){
        val t=track(32768);val first=MediaCache(app);seed(first.stream,t)
        first.downloadManager.release();first.stream.release();first.offline.release()
        space(262146) // Reserve + 8 KiB, while 32 KiB is already stored.
        val second=MediaCache(app)
        try{
            assertTrue(second.hasRoom);assertEquals(40960L,second.streamLimitBytes)
            assertTrue(second.hasCompleteStreamingCache(t))
        }finally{second.downloadManager.release();second.stream.release();second.offline.release()}
    }
    @Test fun freeingSpaceRestoresCachingWithoutRestartAndLowSpaceDoesNotStopPlayback(){
        val t=track();space(262144);val media=app.media
        assertEquals(0L,media.streamLimitBytes)
        media.streamFactory.setUpstreamDataSourceFactory{ByteArrayDataSource(ByteArray(8192){7})}
        assertEquals(8192,read(media,t).size)
        assertFalse(media.hasCompleteStreamingCache(t))
        space(1500000)
        assertTrue(media.streamLimitBytes>=t.size)
        assertEquals(8192,read(media,t).size)
        assertTrue(media.hasCompleteStreamingCache(t))
    }
    @Test fun losingSpaceMidReadCommitsThePrefixButStillDeliversAllAudio(){
        val t=track();val media=app.media
        media.streamFactory.setUpstreamDataSourceFactory {
            val bytes=ByteArrayDataSource(ByteArray(8192){7});var received=0
            object:DataSource by bytes {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    if(received>=4096)space(262144)
                    return bytes.read(buffer,offset,minOf(length,2048)).also{if(it>0)received+=it}
                }
            }
        }
        assertArrayEquals(ByteArray(8192){7},read(media,t))
        assertEquals(4096L,media.stream.getCachedBytes(t.cacheKey,0,t.size))
        assertFalse(media.hasCompleteStreamingCache(t))
    }
}
