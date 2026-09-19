package com.cruisetune.player.dashboard

import java.security.MessageDigest

internal enum class DashboardPlaybackState { PLAYING, PAUSED, BUFFERING }

/** Immutable data captured from the player before any background work begins. */
internal data class DashboardSnapshot(
    val trackId: String,
    val sourcePackage: String,
    val title: String,
    val artist: String,
    val album: String,
    val state: DashboardPlaybackState,
    val durationMs: Long?,
    val positionMs: Long,
    val coverUri: String? = null,
    val generation: Long = 0,
)

internal data class DashboardInput(
    val snapshot: DashboardSnapshot,
    val invalidated: Boolean = false,
    val invalidationReason: String = "",
)

internal object DashboardValues {
    private const val TEXT_LIMIT = 256

    fun text(value: CharSequence?): String {
        if (value == null) return ""
        val output = StringBuilder()
        var offset = 0
        var count = 0
        while (offset < value.length && count < TEXT_LIMIT) {
            val codePoint = Character.codePointAt(value, offset)
            if (!Character.isISOControl(codePoint)) output.appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
            count++
        }
        return output.toString()
    }

    fun duration(value: Long?): Long = when {
        value == null || value <= 0 -> 0L
        else -> value
    }

    fun position(value: Long, duration: Long): Long {
        val safe = value.coerceAtLeast(0).let { position ->
            if (duration > 0) position.coerceAtMost(duration) else position
        }
        return safe
    }

    /** Deterministic positive 64-bit ID; it never exposes a cloud file ID. */
    fun mediaId(sourcePackage: String, trackId: String): Long {
        val source = "$sourcePackage.length=${sourcePackage.toByteArray(Charsets.UTF_8).size}:$sourcePackage" +
            ";track.length=${trackId.toByteArray(Charsets.UTF_8).size}:$trackId"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        var value = 0L
        digest.take(8).forEach { value = (value shl 8) or (it.toLong() and 0xffL) }
        return (value and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
    }
}
