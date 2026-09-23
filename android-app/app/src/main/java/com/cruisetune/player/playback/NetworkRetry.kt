package com.cruisetune.player.playback

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.cruisetune.player.core.InvalidMediaRange
import com.cruisetune.player.core.UserError
import com.cruisetune.player.core.ConfirmedRemoteFileMissing
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException

@UnstableApi
object NetworkRetry {
    fun delayMs(attempt: Int): Long = when (attempt) { 0,1 -> 2000; 2 -> 4000; 3 -> 8000; 4 -> 15000; else -> 30000 }
    fun isTransient(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        if (causes.any { it is CancellationException || it is InvalidMediaRange || it is ConfirmedRemoteFileMissing || it is ParserException || it is SSLHandshakeException || it is SSLPeerUnverifiedException }) return false
        causes.filterIsInstance<UserError>().firstOrNull()?.let { return it.retryable && !it.needsLogin }
        causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let { return it.responseCode in listOf(408, 425, 429) || it.responseCode in 500..599 }
        // HTTP read failures retain their transport wrapper. A bare EOF may come from parsing a
        // fully cached, truncated file; repeatedly opening the same bytes cannot repair it.
        return causes.any { it is UnknownHostException || it is SocketTimeoutException || it is SocketException || it is HttpDataSource.HttpDataSourceException }
    }
}

/** Keep a network-interrupted playback load alive until connectivity returns or the user stops it. */
@UnstableApi
class PersistentNetworkLoadPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        // Bypassing suspect cached bytes does not change the network's retry policy.
        if (NetworkRetry.isTransient(loadErrorInfo.exception))
            NetworkRetry.delayMs(loadErrorInfo.errorCount) else C.TIME_UNSET
}
