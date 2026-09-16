package com.cruisetune.player.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import com.cruisetune.player.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@androidx.media3.common.util.UnstableApi
class WholeTrackCacheAttemptTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun entireTracksAreCachedAndNetworkFailureResumesAfterProcessStyleReopen() = runBlocking {
        val bytes=ByteArray(6*1024*1024+137){(it%251).toByte()}
        val track=Track("1","source","file","Track",size=bytes.size.toLong())
        val opened=mutableListOf<Long>();var fail=true
        val upstream=DataSource.Factory { object:DataSource {
            var position=0; var remaining=0;var openedUri:Uri?=null
            override fun open(spec:DataSpec):Long {opened+=spec.position;openedUri=spec.uri;position=spec.position.toInt();remaining=if(spec.length<0)bytes.size-position else spec.length.toInt();return remaining.toLong()}
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                if(fail && position>=32768){fail=false;throw SocketTimeoutException()}
                if(remaining==0)return C.RESULT_END_OF_INPUT
                val n=minOf(length,remaining,16384);bytes.copyInto(buffer,offset,position,position+n);position+=n;remaining-=n;return n
            }
            override fun getUri()=openedUri
            override fun close(){}
            override fun addTransferListener(listener:TransferListener){}
        } }
        val dir=temporary.newFolder("spans")
        @Suppress("DEPRECATION") var cache=SimpleCache(dir,LeastRecentlyUsedCacheEvictor(20L*1024*1024))
        fun factory()=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        try {
            try{WholeTrackCacheAttempt(track,factory(),track.size,20L*1024*1024){true}.cache();fail("Expected interruption")}catch(_:SocketTimeoutException){}
            val partial=cache.getCachedBytes(track.cacheKey,0,track.size);assertTrue(partial in 1 until track.size)
            cache.release()
            @Suppress("DEPRECATION") val reopened=SimpleCache(dir,LeastRecentlyUsedCacheEvictor(20L*1024*1024));cache=reopened
            WholeTrackCacheAttempt(track,factory(),track.size,20L*1024*1024){true}.cache()
            assertTrue("Must cache beyond the former 1 MiB cap",cache.isCached(track.cacheKey,0,track.size))
            assertEquals(partial,opened.last())
            val reader=CacheDataSource.Factory().setCache(cache).createDataSource()
            reader.open(DataSpec.Builder().setUri("cruisetune://track/1").setKey(track.cacheKey).build())
            val actual=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
            try{while(true){val n=reader.read(buffer,0,buffer.size);if(n<0)break;actual.write(buffer,0,n)}}finally{reader.close()}
            assertArrayEquals(bytes,actual.toByteArray())
            val calls=opened.size
            WholeTrackCacheAttempt(track,factory(),track.size,20L*1024*1024){true}.cache()
            assertEquals("A complete file must not be downloaded again",calls,opened.size)
        } finally {cache.release()}
    }
}
