package com.cruisetune.player.playback

import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.decoder.flac.FlacDecodeErrors
import com.cruisetune.player.core.userError
import com.cruisetune.player.core.confirmedRemoteFileMissing
import java.io.EOFException

/** Budgets survive automatic skips, so repeat modes cannot loop forever through broken files. */
@UnstableApi
internal class PlaybackRecoveryPolicy {
    private val attempted = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()

    fun tryRecovery(key: String): Boolean = attempted.add(key)
    fun failed(key: String) { failed += key }
    fun reset() { attempted.clear(); failed.clear() }

    fun nextIndex(keys: List<String>, currentIndex: Int, next: (Int) -> Int): Int? {
        var index = currentIndex
        repeat(keys.size) {
            index = next(index)
            if (index !in keys.indices) return null
            if (keys[index] !in failed) return index
        }
        return null
    }

    companion object {
        fun isConfirmedMissing(error: Throwable): Boolean = confirmedRemoteFileMissing(error) != null
        fun isProgressFailure(error: Throwable): Boolean = !isSharedFailure(error) &&
            generateSequence(error) { it.cause }.take(16).filterIsInstance<StuckPlayerException>().any {
                it.stuckType == StuckPlayerException.STUCK_PLAYING_NO_PROGRESS ||
                    it.stuckType == StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS ||
                    it.stuckType == StuckPlayerException.STUCK_BUFFERING_NOT_LOADING
            }
        fun isSharedFailure(error: Throwable): Boolean = userError(error) != null || NetworkRetry.isTransient(error) ||
            generateSequence(error) { it.cause }.take(16).any {
                it is HttpDataSource.HttpDataSourceException || it is SecurityException ||
                    it is javax.net.ssl.SSLException || it is com.cruisetune.player.core.InvalidMediaRange ||
                    it is kotlinx.coroutines.CancellationException
            }
        fun isFileFailure(error: Throwable): Boolean {
            if (isSharedFailure(error)) return false
            val causes = generateSequence(error) { it.cause }.take(16).toList()
            return causes.any { it is EOFException || it is ParserException || it is FileDataSource.FileDataSourceException ||
                FlacDecodeErrors.isFrameDecodeFailure(it) }
        }
    }
}
