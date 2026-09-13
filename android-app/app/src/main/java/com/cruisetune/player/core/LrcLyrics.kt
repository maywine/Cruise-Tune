package com.cruisetune.player.core

data class LyricLine(val timeMs: Long, val text: String)
data class LyricFrame(val current: String, val next: String)

class LrcLyrics(val lines: List<LyricLine>, val plainText: String? = null) {
    fun at(positionMs: Long): LyricFrame {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) / 2
            if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
        }
        val current = lines.getOrNull(low - 1)?.text
        var upcoming = low
        while(upcoming < lines.size && lines[upcoming].text.isBlank())upcoming++
        val next = lines.getOrNull(upcoming)?.text.orEmpty()
        return LyricFrame(current?.ifBlank { "间奏" } ?: "即将开始", next)
    }
}

object LrcParser {
    const val MAX_BYTES = 256 * 1024
    private val timestamp = Regex("\\[(\\d{1,6}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTimestamp = Regex("<\\d{1,6}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val offsetTag = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(text: String): LrcLyrics {
        require(text.length <= MAX_BYTES) { "Lyrics text exceeds the size limit" }
        val offset = offsetTag.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
            ?.coerceIn(-86_400_000, 86_400_000) ?: 0
        val lines = mutableListOf<LyricLine>()
        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            val stamps = mutableListOf<MatchResult>()
            var end = 0
            while(end < line.length) {
                val stamp = timestamp.matchAt(line,end) ?: break
                stamps += stamp;end = stamp.range.last + 1
                if(stamps.size > 10000)throw UserError("歌词行数过多")
            }
            if (stamps.isEmpty()) return@forEach
            val words = line.substring(stamps.last().range.last + 1).replace(wordTimestamp, "").trim()
            stamps.forEach stampLoop@ { stamp ->
                val seconds = stamp.groupValues[2].toInt()
                if (seconds >= 60) return@stampLoop
                val fraction = stamp.groupValues[3].padEnd(3, '0').toInt()
                // A positive LRC offset advances the lyrics relative to the audio clock.
                val time = stamp.groupValues[1].toLong() * 60_000 + seconds * 1000 + fraction - offset
                lines += LyricLine(time, words)
                if (lines.size > 10000) throw UserError("歌词行数过多")
            }
        }
        val grouped = lines.sortedBy { it.timeMs }.groupBy { it.timeMs }.map { (time, sameTime) ->
            LyricLine(time, sameTime.map { it.text }.filter { it.isNotBlank() }.distinct().joinToString(" · "))
        }
        return LrcLyrics(grouped)
    }
}
