package com.cruisetune.player.playback

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.cruisetune.player.core.LrcLyrics
import com.cruisetune.player.core.LrcParser
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Reuse extracted audio tags, without another file read or authenticated download. */
@UnstableApi
internal object EmbeddedLyrics {
    private fun isLyricsKey(key: String?): Boolean = key != null && key.length <= 64 &&
        key.uppercase(Locale.ROOT).replace("_", "").replace(" ", "").replace("-", "") in
        setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCHRONIZEDLYRICS", "SYNCEDLYRICS", "SYNCHRONIZEDLYRICS", "USLT", "ULT")

    fun entries(metadata: Metadata?): List<Metadata.Entry> = if (metadata == null) emptyList() else
        (0 until metadata.length()).asSequence().map { metadata[it] }.filter { entry ->
            when (entry) {
                is VorbisComment -> isLyricsKey(entry.key)
                is BinaryFrame -> entry.id == "USLT" || entry.id == "ULT"
                // Media3 maps M4A's ©lyr atom to a USLT text frame, not a binary ID3 frame.
                is TextInformationFrame -> entry.id in setOf("USLT", "ULT") ||
                    (entry.id == "TXXX" && isLyricsKey(entry.description))
                is InternalFrame -> isLyricsKey(entry.description)
                else -> false
            }
        }.filter { entry ->
            // The input/cache must also be bounded, not just the eventual parsed text.
            when (entry) {
                is VorbisComment -> entry.value.length <= LrcParser.MAX_BYTES
                is BinaryFrame -> entry.data.size <= LrcParser.MAX_BYTES
                is TextInformationFrame -> entry.values.sumOf { it.length.toLong() + 1 } <= LrcParser.MAX_BYTES
                is InternalFrame -> entry.text.length <= LrcParser.MAX_BYTES
                else -> false
            }
        }.take(32).toList()

    fun parse(entries: List<Metadata.Entry>): LrcLyrics? {
        var plain: LrcLyrics? = null
        for (entry in entries.take(32)) {
            val result = runCatching {
                val value = when (entry) {
                    is VorbisComment -> entry.value.takeIf { isLyricsKey(entry.key) }
                    is BinaryFrame -> if (entry.id == "USLT" || entry.id == "ULT") decodeUslt(entry.data) else null
                    is TextInformationFrame -> if (entry.id in setOf("USLT", "ULT") ||
                        (entry.id == "TXXX" && isLyricsKey(entry.description))) {
                        if (entry.values.sumOf { it.length.toLong() + 1 } > LrcParser.MAX_BYTES) null
                        else entry.values.joinToString("\n")
                    } else null
                    is InternalFrame -> entry.text.takeIf { isLyricsKey(entry.description) }
                    else -> null
                } ?: return@runCatching null
                if (value.length > LrcParser.MAX_BYTES || value.toByteArray(Charsets.UTF_8).size > LrcParser.MAX_BYTES) return@runCatching null
                val text = value.trim().trim('\u0000', '\uFEFF').trim()
                if (text.isBlank() || text.lineSequence().take(10001).count() > 10000) return@runCatching null
                val synced = LrcParser.parse(text)
                if (synced.lines.isNotEmpty()) synced else LrcLyrics(emptyList(), text)
            }.getOrNull()
            if (result != null) {
                if (result.lines.isNotEmpty()) return result
                if (plain == null) plain = result
            }
        }
        return plain
    }

    private fun decodeUslt(data: ByteArray): String? {
        if (data.size !in 5..LrcParser.MAX_BYTES) return null
        val encoding = data[0].toInt() and 0xff
        val delimiter = if (encoding == 1 || encoding == 2) 2 else 1
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> if (data[4] == 0xff.toByte() && data.getOrNull(5) == 0xfe.toByte()) Charsets.UTF_16LE else Charsets.UTF_16BE
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        // Skip encoding, ISO-639 language and the terminated content descriptor.
        var end = 4
        while (end + delimiter <= data.size) {
            if (data[end] == 0.toByte() && (delimiter == 1 || data[end + 1] == 0.toByte())) break
            end += delimiter
        }
        val start = end + delimiter
        if (start >= data.size) return null
        val textCharset = if (encoding == 1 && start + 1 < data.size &&
            ((data[start] == 0xff.toByte() && data[start + 1] == 0xfe.toByte()) ||
             (data[start] == 0xfe.toByte() && data[start + 1] == 0xff.toByte()))) Charsets.UTF_16 else charset
        return textCharset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, start, data.size - start)).toString()
    }
}
