package com.cruisetune.player.playback

import com.cruisetune.player.core.Track
import kotlinx.coroutines.*

/** One cancellable attempt. The engine joins it before starting a replacement queue. */
interface PrefetchAttempt {
    suspend fun cache()
    fun cancel()
}

/** Items are already in actual playback order, including the player's shuffle order. */
object LookAheadPlan {
    fun select(tracks: List<Track>, index: Int, repeatMode: Int, count: Int = 3, nextIndex: ((Int) -> Int)? = null): List<Track> {
        if (index !in tracks.indices || count <= 0) return emptyList()
        var cursor = index
        return buildList {
            repeat(count) {
                cursor = when {
                    nextIndex != null -> nextIndex(cursor)
                    repeatMode == 1 -> cursor
                    cursor + 1 < tracks.size -> cursor + 1
                    repeatMode == 2 -> 0
                    else -> return@buildList
                }
                if (cursor !in tracks.indices) return@buildList
                add(tracks[cursor])
            }
        }.distinctBy { it.cacheKey }
    }
}

/** Network failures have no attempt limit; each item has its own backoff so one cannot starve the rest. */
@androidx.media3.common.util.UnstableApi
class LookAheadPrefetch(
    private val scope: CoroutineScope,
    private val isComplete: (Track) -> Boolean,
    private val createAttempt: (Track) -> PrefetchAttempt,
    private val now: () -> Long,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val retryDelay: (Int) -> Long = NetworkRetry::delayMs,
    private val report: suspend (String?) -> Unit = {},
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null
    @Volatile private var active: PrefetchAttempt? = null
    private var signature = emptyList<String>()
    private var allowed = false

    /** Called on the playback thread; unrelated progress ticks don't restart downloads/backoff. */
    fun update(targets: List<Track>, canDownload: Boolean) {
        val wanted = targets.filter { it.localUri.isBlank() }.distinctBy { it.cacheKey }
        val keys = wanted.map { it.cacheKey }
        if (keys == signature && canDownload == allowed && job?.isActive == true) return
        val previous = job
        previous?.cancel(); active?.cancel()
        signature = keys; allowed = canDownload
        job = scope.launch {
            previous?.join()
            if (!canDownload || wanted.isEmpty()) { report(null); return@launch }
            val attempts = mutableMapOf<String, Int>()
            val nextAttempt = mutableMapOf<String, Long>()
            val blocked = mutableSetOf<String>()
            val waitingForSpace = mutableSetOf<String>()
            while (isActive) {
                for (track in wanted) {
                    ensureActive()
                    val key = track.cacheKey
                    if (isComplete(track)) { attempts.remove(key); nextAttempt.remove(key); waitingForSpace.remove(key); continue }
                    if (key in blocked || (nextAttempt[key] ?: 0) > now()) continue
                    val attempt = createAttempt(track)
                    active = attempt
                    // Run the blocking cache pump as a child; cancel() releases it before joining.
                    supervisorScope {
                        val work = async(workerDispatcher) { attempt.cache() }
                        try {
                            work.await()
                            ensureActive()
                            if (!isComplete(track)) throw com.cruisetune.player.core.UserError("歌曲缓存尚未完整，将继续重试", retryable = true)
                            attempts.remove(key); nextAttempt.remove(key); waitingForSpace.remove(key)
                        } catch (e: CancellationException) {
                            attempt.cancel(); work.cancel(); throw e
                        } catch (e: Exception) {
                            if (e is CacheStorageUnavailable || NetworkRetry.isTransient(e)) {
                                if (e is CacheStorageUnavailable) waitingForSpace += key else waitingForSpace -= key
                                val count = ((attempts[key] ?: 0) + 1).coerceAtMost(30)
                                attempts[key] = count; nextAttempt[key] = now() + retryDelay(count)
                            } else {
                                blocked += key
                                waitingForSpace -= key
                                report(com.cruisetune.player.core.readableError(e))
                            }
                        } finally {
                            attempt.cancel()
                            withContext(NonCancellable) { work.join() }
                            if (active === attempt) active = null
                        }
                    }
                }
                if (blocked.isEmpty()) {
                    report(when {
                        nextAttempt.isEmpty() -> null
                        waitingForSpace.isNotEmpty() -> "缓存空间不足，后续歌曲将稍后重试"
                        else -> "网络暂不可用，后续歌曲将持续重试缓存"
                    })
                }
                // Recheck disk spans: clearing/evicting cached content must not leave a stale 'done' flag.
                wait(2000)
            }
        }
    }
    fun cancel() { allowed = false; signature = emptyList(); job?.cancel(); active?.cancel() }
    suspend fun cancelAndJoin() { val previous = job; cancel(); previous?.join() }
}
