package com.cruisetune.player.ui

import com.cruisetune.player.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class LyricsStateFlowTest {
    @Test fun switchingTracksCancelsOldLyricsAndNeverDisplaysThemForTheNewSong() = runTest {
        val old=Track("old","source","old","Old")
        val next=old.copy(id="next",fileId="next")
        val tracks=MutableStateFlow<Track?>(old)
        val started=CompletableDeferred<Unit>()
        var cancelled=false
        val received=mutableListOf<LyricsState>()
        val job=launch {
            lyricsStateFlow(tracks) { track ->
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
        val missing=lyricsStateFlow(flowOf(track)){null}.toList().last()
        val untimed=lyricsStateFlow(flowOf(track)){LrcParser.parse("No timestamp")}.toList().last()
        val failed=lyricsStateFlow(flowOf(track)){throw UserError("歌词暂时无法下载")}.toList().last()
        assertEquals("未找到同名 .lrc 歌词",missing.message)
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
            lyricsStateFlow(tracks) { reads++;LrcParser.parse("[00:00]Cached line") }.toList(states)
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
        val original=lyricsStateFlow(flowOf(track),cache) { LrcParser.parse("[00:00]Cached") }.toList().last()
        val restored=lyricsStateFlow(flowOf(track),cache) { error("No reload expected") }.toList()
        assertEquals(listOf(original),restored)
        val next=track.copy(id="next",fileId="next",relativePath="Next.flac")
        val result=lyricsStateFlow(flowOf(next),cache) { LrcParser.parse("[00:00]Next") }.toList().last()
        assertEquals("Next",result.lyrics!!.at(0).current)
        assertNull(cache.get(track.lyricsKey))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun explicitRefreshInvalidatesTheSuccessfulResult() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        var reads=0;var last=LyricsState()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            lyricsStateFlow(flowOf(track),cache,cache.refreshes) { reads++;LrcParser.parse("[00:00]Read $reads") }.collect { last=it }
        }
        runCurrent();assertEquals(1,reads)
        cache.invalidate();runCurrent()
        assertEquals(2,reads);assertEquals("Read 2",last.lyrics!!.at(0).current)
    }
    @Test fun failedLoadsAreNotCached() = runTest {
        val track=Track("song","source","file","Song")
        val cache=LyricsCache()
        lyricsStateFlow(flowOf(track),cache) { throw UserError("读取失败") }.toList()
        assertNull(cache.get(track.lyricsKey))
        val result=lyricsStateFlow(flowOf(track),cache) { LrcParser.parse("[00:00]Recovered") }.toList().last()
        assertEquals("Recovered",result.lyrics!!.at(0).current)
    }
}
