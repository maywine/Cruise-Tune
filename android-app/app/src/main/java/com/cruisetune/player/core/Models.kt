package com.cruisetune.player.core

import java.security.MessageDigest
import java.util.Locale

enum class SourceKind { LOCAL, QUARK, QUARK_OPEN }

data class MusicSource(
    val id: String,
    val kind: SourceKind,
    val title: String,
    val rootId: String,
    val accountId: String = "",
    val recursive: Boolean = true,
)

data class Track(
    val id: String,
    val sourceId: String,
    val fileId: String,
    val title: String,
    val artist: String = "",
    val relativePath: String = title,
    val size: Long = 0,
    val version: String = "",
    val localUri: String = "",
    val mimeType: String = "audio/mpeg",
    val durationMs: Long = 0,
    val contentIdentity: String = "",
) {
    val cacheKey: String get() = stableHash(sourceId, contentIdentity.ifEmpty { fileId }, version, size.toString())
}

data class RemoteEntry(val id: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: String)
data class ReadRequest(val url: String, val headers: Map<String, String>, val credentialGeneration: Long? = null)
interface MusicProvider {
    suspend fun listChildren(parentId: String): List<RemoteEntry>
    suspend fun resolve(fileId: String): ReadRequest
}
data class QueueEntry(val track: Track, val originalIndex: Int)
data class PlaybackSnapshot(
    val revision: Long = 0,
    val entries: List<QueueEntry> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val playIntent: Boolean = false,
    val repeatMode: Int = 0,
    val shuffled: Boolean = false,
)

fun stableHash(vararg parts: String): String {
    // Length prefixes make the identity unambiguous even when a path contains separators.
    val input = parts.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object AudioFiles {
    fun mime(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav", "wave" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        else -> null
    }
    fun displayName(name: String) = name.substringBeforeLast('.', name)
}

object NaturalOrder : Comparator<String> {
    private val chunks = Regex("[0-9]+|[^0-9]+")
    override fun compare(a: String, b: String): Int {
        val left = chunks.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = chunks.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            val result = if (x.first().isDigit() && y.first().isDigit()) {
                val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
                xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
            } else x.compareTo(y)
            if (result != 0) return result
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

object QueuePolicy {
    fun reorder(entries: List<QueueEntry>, currentId: String?, shuffle: Boolean, random: kotlin.random.Random = kotlin.random.Random.Default): Pair<List<QueueEntry>, Int> {
        if (entries.isEmpty()) return emptyList<QueueEntry>() to 0
        val reordered = if (shuffle) {
            val current = entries.find { it.track.id == currentId }
            (listOfNotNull(current) + entries.filter { it.track.id != currentId }.shuffled(random))
        } else entries.sortedBy { it.originalIndex }
        return reordered to reordered.indexOfFirst { it.track.id == currentId }.coerceAtLeast(0)
    }
    fun clampPosition(positionMs: Long, durationMs: Long): Long = positionMs.coerceAtLeast(0).let {
        if (durationMs > 0) it.coerceAtMost((durationMs - 1).coerceAtLeast(0)) else it
    }
}
