package com.cruisetune.player.playback

import com.cruisetune.player.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.media3.common.util.UnstableApi
class LookAheadPrefetchTest {
    private fun tracks() = (0..5).map { Track("$it", "source", "$it", "Song $it", size = 5000000) }
    @Test fun followsNextThreeAndLoopBoundary() {
        val t=tracks()
        assertEquals(listOf("1","2","3"),LookAheadPlan.select(t,0,0).map { it.id })
        assertEquals(listOf("5"),LookAheadPlan.select(t,4,0).map { it.id })
        assertEquals(listOf("0","1","2"),LookAheadPlan.select(t,5,2).map { it.id })
        assertEquals(listOf("3"),LookAheadPlan.select(t,3,1).map { it.id })
        assertTrue(LookAheadPlan.select(t,5,0).isEmpty())
    }
    @Test fun actualShuffleSuccessorsOverrideLinearOrderAndDeduplicate() {
        val t=tracks(); val next=mapOf(0 to 4,4 to 2,2 to 1)
        assertEquals(listOf("4","2","1"),LookAheadPlan.select(t,0,0,nextIndex={next[it] ?: -1}).map { it.id })
        assertEquals(listOf("1","0"),LookAheadPlan.select(t.take(2),0,2).map { it.id })
    }
    @Test fun retriesBeyondOldLimitWithoutStarvingOtherSongs() = runTest {
        val done=mutableSetOf<String>();val calls=mutableMapOf<String,Int>()
        val cache=LookAheadPrefetch(backgroundScope,{it.id in done},{track -> object:PrefetchAttempt {
            override suspend fun cache() { val n=(calls[track.id]?:0)+1;calls[track.id]=n;if(track.id=="1" && n<=8)throw SocketTimeoutException();done+=track.id }
            override fun cancel(){}
        }},{testScheduler.currentTime},retryDelay={10},workerDispatcher=StandardTestDispatcher(testScheduler))
        cache.update(tracks().slice(1..3),true);runCurrent()
        assertTrue(done.containsAll(listOf("2","3")));assertFalse("1" in done)
        repeat(9){advanceTimeBy(2000);runCurrent()}
        assertEquals(setOf("1","2","3"),done);assertEquals(9,calls["1"])
        cache.cancel();runCurrent()
    }
    @Test fun changedQueueCancelsAndJoinsOldReadBeforeStartingNewOne() = runTest {
        val started=mutableListOf<String>();val finished=mutableListOf<String>();var active=0;var maxActive=0
        val cache=LookAheadPrefetch(backgroundScope,{false},{track -> object:PrefetchAttempt {
            val gate=CompletableDeferred<Unit>()
            override suspend fun cache(){ started+=track.id;active++;maxActive=maxOf(maxActive,active);try{gate.await()}finally{active--;finished+=track.id} }
            override fun cancel(){gate.cancel()}
        }},{testScheduler.currentTime},workerDispatcher=StandardTestDispatcher(testScheduler))
        cache.update(tracks().slice(1..3),true);runCurrent();cache.update(tracks().slice(3..5),true);runCurrent()
        assertEquals(listOf("1","3"),started);assertTrue("1" in finished);assertEquals(1,maxActive)
        cache.cancel();runCurrent();assertEquals(0,active)
    }
    @Test fun completeLocalAndEvictedFilesAreHandledWithoutStaleCompletionFlag() = runTest {
        val done=mutableSetOf("1");val calls=mutableListOf<String>()
        val cache=LookAheadPrefetch(backgroundScope,{it.id in done},{track -> object:PrefetchAttempt {
            override suspend fun cache(){calls+=track.id;done+=track.id}
            override fun cancel(){}
        }},{testScheduler.currentTime},workerDispatcher=StandardTestDispatcher(testScheduler))
        cache.update(listOf(tracks()[1],tracks()[2].copy(localUri="content://music/2"),tracks()[3]),true);runCurrent()
        assertEquals(listOf("3"),calls);done.remove("1");advanceTimeBy(2000);runCurrent();assertEquals(listOf("3","1"),calls)
        cache.cancel();runCurrent()
    }
    @Test fun authenticationFailureIsNotRetriedAsNetworkFailure() = runTest {
        var calls=0
        val cache=LookAheadPrefetch(backgroundScope,{false},{object:PrefetchAttempt {
            override suspend fun cache(){calls++;throw UserError("Please sign in",needsLogin=true)}
            override fun cancel(){}
        }},{testScheduler.currentTime},workerDispatcher=StandardTestDispatcher(testScheduler))
        cache.update(listOf(tracks()[1]),true);runCurrent();advanceTimeBy(60000);runCurrent();assertEquals(1,calls)
        cache.cancel();runCurrent()
    }
    @Test fun incompleteCacheCoverageStillRetriesUntilTheWholeTrackIsAvailable() = runTest {
        var calls=0;var complete=false
        val cache=LookAheadPrefetch(backgroundScope,{complete},{object:PrefetchAttempt {
            override suspend fun cache(){calls++;if(calls==3)complete=true}
            override fun cancel(){}
        }},{testScheduler.currentTime},retryDelay={10},workerDispatcher=StandardTestDispatcher(testScheduler))
        cache.update(listOf(tracks()[1]),true);runCurrent()
        assertEquals(1,calls);assertFalse(complete)
        repeat(2){advanceTimeBy(2000);runCurrent()}
        assertTrue(complete);assertEquals(3,calls)
        advanceTimeBy(10000);runCurrent();assertEquals(3,calls)
        cache.cancel();runCurrent()
    }
}
