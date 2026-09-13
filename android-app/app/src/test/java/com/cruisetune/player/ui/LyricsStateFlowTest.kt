package com.cruisetune.player.ui

import com.cruisetune.player.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class LyricsStateFlowTest {
    private fun Flow<Track?>.requests() = map { it?.let(::LyricsRequest) }
    private fun embedded(track:Track,text:String) = LyricsRequest(track,listOf(androidx.media3.extractor.metadata.vorbis.VorbisComment("LYRICS",text)))
    @Test fun embeddedLyricsAvoidSidecarReadsAndSurviveCoverRoundTrips() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        val request=embedded(track,"[00:01]内嵌歌词")
        val state=lyricsStateFlow(flowOf(request),cache) { error("Embedded lyrics must not list or download sidecars") }.toList().last()
        assertTrue(state.embedded)
        assertEquals("内嵌歌词",state.lyrics!!.at(1000).current)
        lyricsStateFlow(flowOf(null),cache) { error("No track") }.toList()
        assertEquals(listOf(state),lyricsStateFlow(flowOf(request),cache) { error("Must reuse cached lyrics") }.toList())
    }
    @Test fun lateEmbeddedMetadataReplacesCachedSidecarAndPlainTextRemainsReadable() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        lyricsStateFlow(flowOf(LyricsRequest(track)),cache) { LrcParser.parse("[00:00]Sidecar") }.toList()
        val state=lyricsStateFlow(flowOf(embedded(track,"内嵌纯文本\n第二行")),cache) { error("Already have lyrics") }.toList().last()
        assertTrue(state.embedded)
        assertEquals("内嵌纯文本\n第二行",state.lyrics!!.plainText)
    }
    @Test fun lateMetadataCancelsTheInFlightSidecarRequest() = runTest {
        val track=Track("song","source","file","Song")
        val requests=MutableStateFlow<LyricsRequest?>(LyricsRequest(track))
        val started=CompletableDeferred<Unit>()
        var cancelled=false
        val result=async {
            lyricsStateFlow(requests) {
                try { started.complete(Unit);awaitCancellation() } finally { cancelled=true }
            }.first { it.embedded }
        }
        started.await();requests.value=embedded(track,"[00:00]Embedded")
        assertEquals("Embedded",result.await().lyrics!!.at(0).current)
        assertTrue(cancelled)
    }
    @Test fun unsupportedMetadataUsesSidecarWithoutDisplayingThePreviousSongsLyrics() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        lyricsStateFlow(flowOf(embedded(track,"Old plain lyrics")),cache) { null }.toList()
        val next=track.copy(id="next",fileId="next")
        val request=LyricsRequest(next,listOf(androidx.media3.extractor.metadata.vorbis.VorbisComment("COMMENT","Not lyrics")))
        val state=lyricsStateFlow(flowOf(request),cache) { LrcParser.parse("[00:00]New sidecar") }.toList().last()
        assertFalse(state.embedded)
        assertNull(state.lyrics!!.plainText)
        assertEquals("New sidecar",state.lyrics.at(0).current)
    }
    @Test fun switchingTracksCancelsOldLyricsAndNeverDisplaysThemForTheNewSong() = runTest {
        val old=Track("old","source","old","Old")
        val next=old.copy(id="next",fileId="next")
        val tracks=MutableStateFlow<Track?>(old)
        val started=CompletableDeferred<Unit>()
        var cancelled=false
        val received=mutableListOf<LyricsState>()
        val job=launch {
            lyricsStateFlow(tracks.requests()) { track ->
                if(track.id==old.id) {
                    try { started.complete(Unit);awaitCancellation() } finally { cancelled=true }
                }
                LrcParser.parse("[00:00]New song")
            }.take(3).toList(received)
        }
        started.await();tracks.value=next;job.join()
        assertTrue(cancelled)
        assertEquals(listOf(old.lyricsKey,next.lyricsKey,next.lyricsKey),received.map { it.key })
        assertEquals("New song",received.last().lyrics!!.at(0).current)
    }
    @Test fun missingUntimedAndFailedLyricsHaveDistinctStates() = runTest {
        val track=Track("song","source","file","Song")
        val missing=lyricsStateFlow(flowOf(track).requests()){null}.toList().last()
        val untimed=lyricsStateFlow(flowOf(track).requests()){LrcParser.parse("No timestamp")}.toList().last()
        val failed=lyricsStateFlow(flowOf(track).requests()){throw UserError("歌词暂时无法下载")}.toList().last()
        assertEquals("未找到内嵌歌词或同名 .lrc 歌词",missing.message)
        assertEquals("歌词没有可识别的时间标记",untimed.message)
        assertEquals("歌词暂时无法下载",failed.message)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun coverRoundTripRetainsLyricsWithoutLoadingAgain() = runTest {
        val track=Track("song","source","file","Song")
        val tracks=MutableStateFlow<Track?>(track)
        var reads=0
        val states=mutableListOf<LyricsState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            lyricsStateFlow(tracks.requests()) { reads++;LrcParser.parse("[00:00]Cached line") }.toList(states)
        }
        runCurrent()
        val parsed=states.last().lyrics
        tracks.value=null;runCurrent()
        val before=states.size
        tracks.value=track;runCurrent()
        assertEquals(1,reads)
        assertSame(parsed,states.last().lyrics)
        assertEquals(1,states.size-before)
    }
    @Test fun recreatedCollectorReusesCacheButDifferentTrackDoesNot() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        val original=lyricsStateFlow(flowOf(track).requests(),cache) { LrcParser.parse("[00:00]Cached") }.toList().last()
        val restored=lyricsStateFlow(flowOf(track).requests(),cache) { error("No reload expected") }.toList()
        assertEquals(listOf(original),restored)
        val next=track.copy(id="next",fileId="next",relativePath="Next.flac")
        val result=lyricsStateFlow(flowOf(next).requests(),cache) { LrcParser.parse("[00:00]Next") }.toList().last()
        assertEquals("Next",result.lyrics!!.at(0).current)
        assertNull(cache.get(track.lyricsKey))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun explicitRefreshInvalidatesTheSuccessfulResult() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        var reads=0;var last=LyricsState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            lyricsStateFlow(flowOf(track).requests(),cache,cache.refreshes) { reads++;LrcParser.parse("[00:00]Read $reads") }.collect { last=it }
        }
        runCurrent();assertEquals(1,reads)
        cache.invalidate();runCurrent()
        assertEquals(2,reads);assertEquals("Read 2",last.lyrics!!.at(0).current)
    }
    @Test fun failedLoadsAreNotCached() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        lyricsStateFlow(flowOf(track).requests(),cache) { throw UserError("读取失败") }.toList()
        assertNull(cache.get(track.lyricsKey))
        val result=lyricsStateFlow(flowOf(track).requests(),cache) { LrcParser.parse("[00:00]Recovered") }.toList().last()
        assertEquals("Recovered",result.lyrics!!.at(0).current)
    }
}
