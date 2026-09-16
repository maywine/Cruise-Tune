package com.cruisetune.player.ui

import com.cruisetune.player.core.*
import androidx.lifecycle.ViewModel
import androidx.media3.common.Metadata
import com.cruisetune.player.playback.EmbeddedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

internal data class LyricsState(val key: String? = null, val lyrics: LrcLyrics? = null, val message: String = "选择一首歌曲", val embedded: Boolean = false)
internal val Track.lyricsKey: String get() = stableHash(cacheKey,relativePath)
@androidx.media3.common.util.UnstableApi
internal data class LyricsRequest(val track: Track, val metadata: List<Metadata.Entry>? = emptyList())

/** Keep one successful result across view changes and Activity recreation, never failures. */
@androidx.media3.common.util.UnstableApi
internal class LyricsCache : ViewModel() {
    private var cached: LyricsState? = null
    private var metadata: List<Metadata.Entry>? = emptyList()
    private val refreshMutable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshes = refreshMutable.asSharedFlow()
    fun get(key: String?): LyricsState? = cached?.takeIf { key != null && it.key == key }
    fun get(request: LyricsRequest): LyricsState? = get(request.track.lyricsKey)?.takeIf {
        request.metadata == null || request.metadata == metadata
    }
    fun remember(state: LyricsState, entries: List<Metadata.Entry>? = emptyList()) {
        if (state.lyrics != null) { cached = state; metadata = entries }
    }
    fun discardEmbedded(key: String) {
        if(cached?.key==key && cached?.embedded==true) { cached=null;metadata=emptyList() }
    }
    fun invalidate() { cached = null; metadata = emptyList(); refreshMutable.tryEmit(Unit) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.media3.common.util.UnstableApi
internal fun lyricsStateFlow(tracks: Flow<LyricsRequest?>, cache: LyricsCache = LyricsCache(),
    refreshes: Flow<Unit> = emptyFlow(), load: suspend (Track) -> LrcLyrics?): Flow<LyricsState> =
    tracks.distinctUntilChanged().flatMapLatest { request ->
        if (request == null) flowOf(LyricsState())
        else merge(flowOf(Unit), refreshes).flatMapLatest {
            flow {
                val track=request.track
                val key=track.lyricsKey
                cache.get(request)?.let { emit(it); return@flow }
                if(request.metadata!=null)cache.discardEmbedded(key)
                emit(LyricsState(key, message = "正在读取歌词…"))
                try {
                    val embedded = if (request.metadata.isNullOrEmpty()) null else withContext(Dispatchers.Default) { EmbeddedLyrics.parse(request.metadata) }
                    val lyrics = embedded ?: cache.get(key)?.takeUnless { it.embedded }?.lyrics ?: load(track)
                    val state = when {
                        lyrics == null -> LyricsState(key, message = "未找到内嵌歌词或同名 .lrc 歌词")
                        lyrics.lines.isEmpty() && lyrics.plainText.isNullOrBlank() -> LyricsState(key, message = "歌词没有可识别的时间标记")
                        else -> LyricsState(key, lyrics, "", embedded != null)
                    }
                    currentCoroutineContext().ensureActive()
                    cache.remember(state, request.metadata)
                    emit(state)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { emit(LyricsState(key, message = readableError(e))) }
            }
        }
    }
