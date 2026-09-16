package com.cruisetune.player.core

import org.junit.Assert.*
import org.junit.Test

class TrackSortTest {
    private val tracks = listOf(
        Track("ten", "source", "ten", "Song 10", relativePath = "A/Song 10.flac"),
        Track("two", "source", "two", "Song 2", relativePath = "B/Song 2.flac"),
        Track("one", "source", "one", "Song 1", relativePath = "A/Song 1.flac")
    )

    @Test fun titlesAndPathsUseNaturalOrderInBothDirections() {
        assertEquals(listOf("one", "two", "ten"), TrackSort.TITLE_ASC.sorted(tracks).map { it.id })
        assertEquals(listOf("ten", "two", "one"), TrackSort.TITLE_DESC.sorted(tracks).map { it.id })
        assertEquals(listOf("one", "ten", "two"), TrackSort.PATH_ASC.sorted(tracks).map { it.id })
        assertEquals(listOf("two", "ten", "one"), TrackSort.PATH_DESC.sorted(tracks).map { it.id })
    }

    @Test fun duplicateNamesFromDifferentSourcesHaveStableOrder() {
        val sameName = listOf(tracks[0].copy(id="b",sourceId="second"), tracks[0].copy(id="a"))
        assertEquals(listOf("a", "b"), TrackSort.TITLE_ASC.sorted(sameName).map { it.id })
        assertEquals(TrackSort.TITLE_ASC.sorted(sameName), TrackSort.TITLE_ASC.sorted(sameName.reversed()))
    }

    @Test fun sortingQueuePreservesCurrentTrackPositionAndPlaybackMode() {
        for (playing in listOf(false, true)) {
            val before = PlaybackSnapshot(3, tracks.mapIndexed { i, t -> QueueEntry(t, i) }, 1, 42000, playing, 2, true)
            val sorted = TrackSort.PATH_ASC.queue(before, 4)
            assertEquals("two", sorted.entries[sorted.index].track.id)
            assertEquals(42000L, sorted.positionMs)
            assertEquals(playing, sorted.playIntent)
            assertEquals(2, sorted.repeatMode)
            assertEquals(4L, sorted.revision)
            assertFalse(sorted.shuffled)
            // Turning shuffle off must return to the selected sort order.
            val (shuffled, _) = QueuePolicy.reorder(sorted.entries, "two", true)
            assertEquals(sorted.entries, QueuePolicy.reorder(shuffled, "two", false).first)
        }
    }

    @Test fun emptyQueueAndUnknownPreferenceRemainUsable() {
        assertEquals(emptyList<QueueEntry>(), TrackSort.TITLE_ASC.queue(PlaybackSnapshot(), 1).entries)
        assertEquals(TrackSort.PATH_ASC, TrackSort.fromPreference("missing"))
        assertEquals(TrackSort.PATH_ASC, TrackSort.fromPreference(null))
        TrackSort.entries.forEach { assertEquals(it, TrackSort.fromPreference(it.name)) }
    }
}
