package com.cruisetune.player.core

object SourceRemoval {
    fun queue(snapshot: PlaybackSnapshot, sources: Set<String>, revision: Long): PlaybackSnapshot {
        val remaining = snapshot.entries.filter { it.track.sourceId !in sources }
        val current = snapshot.entries.getOrNull(snapshot.index)?.track?.id
        val keptIndex = remaining.indexOfFirst { it.track.id == current }
        val index = if (keptIndex >= 0) keptIndex else snapshot.entries.take(snapshot.index)
            .count { it.track.sourceId !in sources }.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
        return snapshot.copy(revision = revision, entries = remaining, index = index,
            positionMs = if (keptIndex >= 0) snapshot.positionMs else 0,
            playIntent = keptIndex >= 0 && snapshot.playIntent)
    }
}
