package com.cruisetune.player.ui

import com.cruisetune.player.core.*
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

internal data class LyricsState(val key: String? = null, val lyrics: LrcLyrics? = null, val message: String = "选择一首歌曲")
internal val Track.lyricsKey: String get() = stableHash(cacheKey,relativePath)

/** Keep one successful result across view changes and Activity recreation, never failures. */
internal class LyricsCache : ViewModel() {
    private var cached: LyricsState? = null
    private val refreshMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshes = refreshMutable.asSharedFlow()
    fun get(key: String?): LyricsState? = cached?.takeIf { key != null && it.key == key }
    fun remember(state: LyricsState) { if (state.lyrics != null) cached = state }
    fun invalidate() { cached = null; refreshMutable.tryEmit(Unit) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun lyricsStateFlow(tracks: Flow<Track?>, cache: LyricsCache = LyricsCache(),
    refreshes: Flow<Unit> = emptyFlow(), load: suspend (Track) -> LrcLyrics?): Flow<LyricsState> =
    tracks.distinctUntilChangedBy { it?.lyricsKey }.flatMapLatest { track ->
        if (track == null) flowOf(LyricsState())
        else merge(flowOf(Unit), refreshes).flatMapLatest {
            flow {
                val key=track.lyricsKey
                cache.get(key)?.let { emit(it); return@flow }
                emit(LyricsState(key, message = "正在读取歌词…"))
                try {
                    val lyrics = load(track)
                    val state = when {
                        lyrics == null -> LyricsState(key, message = "未找到同名 .lrc 歌词")
                        lyrics.lines.isEmpty() -> LyricsState(key, message = "歌词没有可识别的时间标记")
                        else -> LyricsState(key, lyrics, "")
                    }
                    currentCoroutineContext().ensureActive()
                    cache.remember(state)
                    emit(state)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { emit(LyricsState(key, message = readableError(e))) }
            }
        }
    }
