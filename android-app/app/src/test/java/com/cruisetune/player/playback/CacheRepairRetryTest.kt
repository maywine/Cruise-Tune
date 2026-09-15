package com.cruisetune.player.playback

import com.cruisetune.player.core.UserError
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.media3.common.util.UnstableApi
class CacheRepairRetryTest {
    @Test fun networkAndSpaceFailuresRetryBeyondOldLimitsAndEventuallyComplete()=runTest{
        var attempts=0;val waits=mutableListOf<Long>()
        retryCacheRepair(repair={attempts++;if(attempts<=4)throw SocketTimeoutException();if(attempts<=8)throw CacheStorageUnavailable()},onRetry={},wait={waits+=it})
        assertEquals(9,attempts);assertEquals(listOf(2000L,4000L,8000L,15000L,30000L,30000L,30000L,30000L),waits)
    }
    @Test fun cancellationPreventsALaterRepairAttempt()=runTest{
        var attempts=0
        val job=backgroundScope.launch{retryCacheRepair({attempts++;throw SocketTimeoutException()},{})}
        runCurrent();assertEquals(1,attempts);job.cancel();advanceTimeBy(60000);runCurrent();assertEquals(1,attempts)
    }
    @Test fun authenticationAndPermanentFileErrorsDoNotLoop()=runTest{
        for(error in listOf(UserError("Login",needsLogin=true),UserError("Version changed"))){
            var attempts=0
            val result=runCatching{retryCacheRepair({attempts++;throw error},{},wait={fail("Must not wait")})}
            assertSame(error,result.exceptionOrNull());assertEquals(1,attempts)
        }
    }
}
