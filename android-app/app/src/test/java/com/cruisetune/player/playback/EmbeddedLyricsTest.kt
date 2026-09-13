package com.cruisetune.player.playback

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.cruisetune.player.core.LrcParser
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

@androidx.media3.common.util.UnstableApi
class EmbeddedLyricsTest {
    @Test fun vorbisKeysAreCaseInsensitiveAndTimedLyricsWinOverPlainText() {
        val result=EmbeddedLyrics.parse(listOf(VorbisComment("LYRICS","Plain words"),
            VorbisComment("synced_lyrics","[00:01]第一句\n[00:04]第二句")))!!
        assertNull(result.plainText)
        assertEquals("第一句",result.at(1000).current)
        assertEquals("第二句",result.at(1000).next)
    }
    @Test fun plainLyricsKeepLineBreaksAndHaveNoArtificialTimeline() {
        val result=EmbeddedLyrics.parse(listOf(VorbisComment("UNSYNCED LYRICS","\uFEFF第一行\n第二行\u0000")))!!
        assertTrue(result.lines.isEmpty())
        assertEquals("第一行\n第二行",result.plainText)
    }
    @Test fun nonLyricTagsAndUnsupportedFramesAreNeverDisplayed() {
        val entries=EmbeddedLyrics.entries(Metadata(VorbisComment("COMMENT","Not lyrics"),
            VorbisComment("ARTIST","Artist"),BinaryFrame("SYLT",byteArrayOf(0,1)),
            TextInformationFrame("TXXX","COMMENT",ImmutableList.of("Not lyrics"))))
        assertTrue(entries.isEmpty())
        assertNull(EmbeddedLyrics.parse(entries))
    }
    private fun uslt(encoding:Int, charset:Charset, text:String, description:String="Description", id:String="USLT"):BinaryFrame {
        val delimiter=if(encoding==1 || encoding==2)byteArrayOf(0,0)else byteArrayOf(0)
        return BinaryFrame(id,byteArrayOf(encoding.toByte())+"eng".toByteArray()+description.toByteArray(charset)+delimiter+text.toByteArray(charset))
    }
    @Test fun mp3UsltSupportsLatin1Utf8AndBothUtf16ByteOrders() {
        for((encoding,charset) in listOf(0 to Charsets.ISO_8859_1,3 to Charsets.UTF_8,1 to Charsets.UTF_16,2 to Charsets.UTF_16BE)) {
            val text=if(encoding==0)"Café\nSecond line"else "合成歌词\n第二行"
            assertEquals(text,EmbeddedLyrics.parse(listOf(uslt(encoding,charset,text)))!!.plainText)
        }
        val littleEndian=BinaryFrame("USLT",byteArrayOf(1)+"zho".toByteArray()+byteArrayOf(-1,-2,0,0)+
            "[00:01]小端歌词".toByteArray(Charsets.UTF_16LE))
        assertEquals("小端歌词",EmbeddedLyrics.parse(listOf(littleEndian))!!.at(1000).current)
    }
    @Test fun oldId3UltAndLrcTextInsideUsltAreSupported() {
        assertEquals("Current",EmbeddedLyrics.parse(listOf(uslt(3,Charsets.UTF_8,"[00:01]Current\n[00:03]Next",id="ULT")))!!.at(1000).current)
    }
    @Test fun m4aLyricsAndCustomTextTagsUseTheirDecodedValues() {
        for(entry in listOf(TextInformationFrame("USLT",null,ImmutableList.of("[00:02]M4A line")),
            TextInformationFrame("TXXX","LYRICS",ImmutableList.of("[00:02]M4A line")),
            TextInformationFrame("TXXX","USLT",ImmutableList.of("[00:02]M4A line")),
            InternalFrame("com.apple.iTunes","LYRICS","[00:02]M4A line"))) {
            assertEquals("M4A line",EmbeddedLyrics.parse(EmbeddedLyrics.entries(Metadata(entry)))!!.at(2000).current)
        }
    }
    @Test fun malformedFramesDoNotHideAnotherUsableTag() {
        val broken=listOf(BinaryFrame("USLT",byteArrayOf(3,1)),
            BinaryFrame("USLT",byteArrayOf(3)+"engNo delimiter".toByteArray()),
            BinaryFrame("USLT",byteArrayOf(9)+"eng\u0000ignored".toByteArray()),
            BinaryFrame("USLT",byteArrayOf(1)+"eng".toByteArray()+byteArrayOf(0,0,0)),
            VorbisComment("LYRICS"," "),VorbisComment("LYRICS","[00:00]Valid"))
        assertEquals("Valid",EmbeddedLyrics.parse(broken)!!.at(0).current)
    }
    @Test fun oversizedTextBinaryAndCueCountsAreBounded() {
        assertTrue(EmbeddedLyrics.entries(Metadata(VorbisComment("LYRICS","x".repeat(LrcParser.MAX_BYTES+1)),
            BinaryFrame("USLT",ByteArray(LrcParser.MAX_BYTES+1)))).isEmpty())
        assertNull(EmbeddedLyrics.parse(listOf(VorbisComment("LYRICS","x".repeat(LrcParser.MAX_BYTES+1)))))
        assertNull(EmbeddedLyrics.parse(listOf(VorbisComment("LYRICS","中".repeat(LrcParser.MAX_BYTES/2)))))
        assertNull(EmbeddedLyrics.parse(listOf(BinaryFrame("USLT",ByteArray(LrcParser.MAX_BYTES+1)))))
        assertNull(EmbeddedLyrics.parse(listOf(VorbisComment("LYRICS","[00:01]x\n".repeat(10001)))))
    }
}
