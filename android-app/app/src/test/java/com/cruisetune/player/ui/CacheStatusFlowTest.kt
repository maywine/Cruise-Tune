package com.cruisetune.player.ui

import com.cruisetune.player.core.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CacheStatusFlowTest {
    @Test fun cacheQueryRunsOffTheUiCollectorThread() = runBlocking {
        val collectorThread=Thread.currentThread();val queryThread=AtomicReference<Thread>()
        val track=Track("song","source","file","Song")
        val result=cacheStatusFlow(flowOf(track),{queryThread.set(Thread.currentThread());"已缓存完整"}).first()
        assertNotSame(collectorThread,queryThread.get())
        assertEquals(track.cacheKey,result.key);assertEquals("已缓存完整",result.text)
    }
    @Test fun slowOldTrackResultCannotOverwriteNewTrack() = runBlocking {
        val old=Track("old","source","old-file","Old");val next=old.copy(id="next",fileId="next-file")
        val input=MutableStateFlow<Track?>(old);val started=CountDownLatch(1);val release=CountDownLatch(1)
        val result=async {
            cacheStatusFlow(input,{track ->
                if(track.id=="old"){started.countDown();check(release.await(3,TimeUnit.SECONDS))}
                track.id
            }).first()
        }
        try {
            assertTrue(withContext(Dispatchers.IO){started.await(3,TimeUnit.SECONDS)})
            input.value=next;yield();release.countDown()
            val received=withTimeout(3000){result.await()}
            assertEquals(next.cacheKey,received.key);assertEquals("next",received.text)
        } finally {release.countDown();result.cancelAndJoin()}
    }
    @Test fun cacheReadErrorIsNotReportedAsOnlinePlayback() = runBlocking {
        val status=cacheStatusFlow(flowOf(Track("song","source","file","Song")),{throw java.io.IOException()}).first()
        assertEquals("缓存状态暂不可用",status.text)
    }
}
