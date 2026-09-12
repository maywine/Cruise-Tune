package com.cruisetune.player.core

import org.junit.Assert.*
import org.junit.Test

class LrcLyricsTest {
    @Test fun findsCurrentAndNextAcrossSeekPauseAndEnd() {
        val lyrics=LrcParser.parse("[00:01.20]First\n[00:05]Second\n[00:10.123]Last")
        assertEquals(LyricFrame("即将开始","First"),lyrics.at(0))
        assertEquals(LyricFrame("First","Second"),lyrics.at(1200))
        assertEquals(LyricFrame("Second","Last"),lyrics.at(5000))
        assertEquals(LyricFrame("First","Second"),lyrics.at(2000))
        assertEquals(lyrics.at(2000),lyrics.at(2000))
        assertEquals(LyricFrame("Last",""),lyrics.at(20000))
    }
    @Test fun parsesBomMultipleTimesTranslationsAndInstrumentalGaps() {
        val lyrics=LrcParser.parse("\uFEFF[ar:Artist]\r\n[00:01.2][00:06.200]Hello\n[00:01.200]你好\n[00:04.000]\n[00:08]End")
        assertEquals(listOf(1200L,4000L,6200L,8000L),lyrics.lines.map { it.timeMs })
        assertEquals(LyricFrame("Hello · 你好","Hello"),lyrics.at(1200))
        assertEquals(LyricFrame("间奏","Hello"),lyrics.at(4000))
    }
    @Test fun positiveOffsetAdvancesAndNegativeOffsetDelays() {
        assertEquals("Line",LrcParser.parse("[00:02]Line\n[offset:500]").at(1500).current)
        assertEquals("即将开始",LrcParser.parse("[offset:-500]\n[00:02]Line").at(2000).current)
        assertEquals("Line",LrcParser.parse("[offset:-500]\n[00:02]Line").at(2500).current)
    }
    @Test fun ignoresBrokenTagsAndKeepsLiteralText() {
        val lyrics=LrcParser.parse("[ti:Title]\nUnscheduled line\n[00:99]bad\n[00:01.123]<00:01.200>A [00:09] bracket\n[999999:59.999]Long")
        assertEquals(2,lyrics.lines.size)
        assertEquals("A [00:09] bracket",lyrics.at(1123).current)
        assertTrue(LrcParser.parse("[ar:Artist]\nOnly plain text").lines.isEmpty())
    }
    @Test fun oversizedInputAndExcessCuesAreBounded() {
        assertTrue(runCatching { LrcParser.parse("a".repeat(LrcParser.MAX_BYTES+1)) }.isFailure)
        assertTrue(runCatching { LrcParser.parse("[00:01]".repeat(10001)) }.exceptionOrNull() is UserError)
    }
}
