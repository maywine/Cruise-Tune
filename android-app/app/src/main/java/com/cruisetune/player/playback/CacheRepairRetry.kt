package com.cruisetune.player.playback

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal class CacheStorageUnavailable : IOException("缓存空间不足，已保留原缓存")

/** The repair has its own retry lifetime; it must not suspend playback or future-song caching. */
@androidx.media3.common.util.UnstableApi
internal suspend fun retryCacheRepair(
    repair: suspend () -> Unit,
    onRetry: suspend (Exception) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    var failures = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try { repair(); currentCoroutineContext().ensureActive(); return }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (e !is CacheStorageUnavailable && !NetworkRetry.isTransient(e)) throw e
            failures = (failures + 1).coerceAtMost(5)
            onRetry(e)
            wait(NetworkRetry.delayMs(failures))
        }
    }
}
