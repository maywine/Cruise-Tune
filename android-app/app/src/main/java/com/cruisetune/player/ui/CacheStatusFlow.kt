package com.cruisetune.player.ui

import com.cruisetune.player.core.Track
import com.cruisetune.player.core.StorageStatus
import com.cruisetune.player.core.OfflineState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class TrackCacheStatus(val key: String?, val text: String, val offline: OfflineState = OfflineState.UNAVAILABLE)

/** Disk/cache queries never run on the UI dispatcher; stale track results are cancelled. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun cacheStatusFlow(tracks: Flow<Track?>, query: (Track) -> StorageStatus,
    dispatcher: CoroutineDispatcher = Dispatchers.IO, intervalMs: Long = 1000): Flow<TrackCacheStatus> =
    tracks.distinctUntilChangedBy { it?.cacheKey }.flatMapLatest { track ->
        flow {
            if (track == null) { emit(TrackCacheStatus(null, "")); return@flow }
            while (currentCoroutineContext().isActive) {
                val status = try { withContext(dispatcher) { query(track) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { StorageStatus("缓存状态暂不可用", OfflineState.UNAVAILABLE) }
                emit(TrackCacheStatus(track.cacheKey, status.text, status.offline))
                delay(intervalMs)
            }
        }
    }.distinctUntilChanged()
