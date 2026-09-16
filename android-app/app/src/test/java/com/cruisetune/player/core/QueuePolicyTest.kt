package com.cruisetune.player.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QueuePolicyTest {
    private fun track(id: String, version: String = "1") = Track(id, "source", id, id, size = 20, version = version)
    @Test fun shuffleKeepsCurrentSongAndRoundTripsOriginalOrder() {
        val original = (0..29).map { QueueEntry(track(it.toString()), it) }
        val (shuffled, index) = QueuePolicy.reorder(original, "12", true, Random(7))
        assertEquals("12", shuffled[index].track.id)
        assertEquals(original.map { it.track.id }.toSet(), shuffled.map { it.track.id }.toSet())
        assertNotEquals(original, shuffled)
        val (restored, restoredIndex) = QueuePolicy.reorder(shuffled, "12", false)
        assertEquals(original, restored); assertEquals(12, restoredIndex)
    }
    @Test fun shuffleHandlesEmptyAndMissingCurrentItem() {
        assertTrue(QueuePolicy.reorder(emptyList(), null, true).first.isEmpty())
        val entries = listOf(QueueEntry(track("a"), 0), QueueEntry(track("b"), 1))
        assertEquals(2, QueuePolicy.reorder(entries, "removed", true).first.size)
    }
    @Test fun identitiesCannotMixAccountsOrFileVersions() {
        assertNotEquals(track("1").cacheKey, track("1", "2").cacheKey)
        assertNotEquals(track("1").cacheKey, track("1").copy(sourceId = "other-account").cacheKey)
        assertNotEquals(stableHash("a:b", "c"), stableHash("a", "b:c"))
        assertEquals(track("1").cacheKey, track("1").copy(title = "renamed").cacheKey)
    }
    @Test fun naturalSortHandlesLongTrackNumbersWithoutIntegerOverflow() {
        val names = listOf("曲目10.flac", "曲目2.flac", "曲目1.flac", "曲目999999999999999999999.flac")
        assertEquals(listOf("曲目1.flac", "曲目2.flac", "曲目10.flac", "曲目999999999999999999999.flac"), names.sortedWith(NaturalOrder))
    }
    @Test fun progressIsClampedOnlyWhenDurationIsKnown() {
        assertEquals(0, QueuePolicy.clampPosition(-4, 100))
        assertEquals(99, QueuePolicy.clampPosition(200, 100))
        assertEquals(200, QueuePolicy.clampPosition(200, 0))
    }
    @Test fun supportedExtensionsAreCaseInsensitiveAndNoVideoFallback() {
        assertEquals("audio/flac", AudioFiles.mime("歌曲.FLAC"))
        assertEquals("audio/mp4", AudioFiles.mime("track.m4a"))
        assertNull(AudioFiles.mime("cover.jpg"))
        assertNull(AudioFiles.mime("movie.mp4"))
        assertNull(AudioFiles.mime("track.ape"))
    }
}
