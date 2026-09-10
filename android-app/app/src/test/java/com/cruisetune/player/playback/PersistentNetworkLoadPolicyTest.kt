package com.cruisetune.player.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.cruisetune.player.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
@androidx.media3.common.util.UnstableApi
class PersistentNetworkLoadPolicyTest {
    private fun info(error:IOException,count:Int)=LoadErrorHandlingPolicy.LoadErrorInfo(LoadEventInfo(1,DataSpec.Builder().setUri("https://music.test/song").build(),0),MediaLoadData(C.DATA_TYPE_MEDIA),error,count)
    @Test fun playbackNetworkRetryHasNoAttemptCutoffAndHasCappedDelay(){
        val p=PersistentNetworkLoadPolicy()
        assertEquals(Int.MAX_VALUE,p.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(2000,p.getRetryDelayMsFor(info(SocketTimeoutException(),1)))
        assertEquals(30000,p.getRetryDelayMsFor(info(SocketTimeoutException(),1000000)))
    }
    @Test fun transientServerErrorsRetryButAuthCorruptionAndMissingFilesDoNot(){
        val spec=DataSpec.Builder().setUri("https://music.test/song").build()
        for(code in listOf(408,429,500,503))assertTrue(NetworkRetry.isTransient(HttpDataSource.InvalidResponseCodeException(code,"",null,emptyMap(),spec,byteArrayOf())))
        for(code in listOf(401,403,404,410))assertFalse(NetworkRetry.isTransient(HttpDataSource.InvalidResponseCodeException(code,"",null,emptyMap(),spec,byteArrayOf())))
        assertTrue(NetworkRetry.isTransient(UserError("Busy",retryable=true)))
        val p=PersistentNetworkLoadPolicy()
        assertEquals(C.TIME_UNSET,p.getRetryDelayMsFor(info(UserError("Expired",needsLogin=true),1)))
        assertEquals(C.TIME_UNSET,p.getRetryDelayMsFor(info(InvalidMediaRange(),1)))
        assertFalse(NetworkRetry.isTransient(java.io.FileNotFoundException()))
    }
}
