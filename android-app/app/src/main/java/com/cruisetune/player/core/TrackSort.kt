package com.cruisetune.player.core

enum class TrackSort(val label: String) {
    PATH_ASC("目录 / 文件名升序"),
    PATH_DESC("目录 / 文件名降序"),
    TITLE_ASC("歌曲名称升序"),
    TITLE_DESC("歌曲名称降序");

    fun sorted(tracks: List<Track>): List<Track> {
        val byTitle = this == TITLE_ASC || this == TITLE_DESC
        val comparator = Comparator<Track> { a, b ->
            NaturalOrder.compare(if (byTitle) a.title else a.relativePath, if (byTitle) b.title else b.relativePath)
                .takeIf { it != 0 } ?: NaturalOrder.compare(a.relativePath, b.relativePath)
                .takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
        return tracks.sortedWith(if (this == PATH_DESC || this == TITLE_DESC) Comparator { a, b -> comparator.compare(b, a) } else comparator)
    }

    fun queue(snapshot: PlaybackSnapshot, revision: Long): PlaybackSnapshot {
        val currentId = snapshot.entries.getOrNull(snapshot.index)?.track?.id
        val ordered = sorted(snapshot.entries.map { it.track }).mapIndexed { index, track -> QueueEntry(track, index) }
        return snapshot.copy(revision = revision, entries = ordered,
            index = ordered.indexOfFirst { it.track.id == currentId }.coerceAtLeast(0), shuffled = false)
    }

    companion object {
        const val PREFERENCE = "trackSort"
        const val QUEUE_PREFERENCE = "queueSort"
        fun fromPreference(value: String?): TrackSort = entries.firstOrNull { it.name == value } ?: PATH_ASC
    }
}
