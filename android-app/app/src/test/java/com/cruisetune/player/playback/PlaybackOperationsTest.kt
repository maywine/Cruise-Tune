package com.cruisetune.player.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.cruisetune.player.core.QueueEntry
import com.cruisetune.player.core.QueuePolicy
import com.cruisetune.player.core.Track
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class PlaybackOperationsTest {
    private val entries = (0..5).map { QueueEntry(Track("$it", "source", "$it", "Track $it"), it) }

    @Test fun retryStopsTheOldLoadBeforePreparingAndPreservesSelectionAndPosition() {
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_IDLE)) {
            for (playing in listOf(false, true)) {
                val engine = RecordingPlayer(entries, state = state, playing = playing)
                val before = engine.items.toList()
                PlaybackOperations.retry(engine.player)
                assertEquals(listOf("stop", "seek:2:12000", "prepare", "play"), engine.operations)
                assertEquals(before, engine.items)
                assertEquals("2", engine.currentId)
                assertEquals(12000L, engine.position)
                assertTrue(engine.playing)
            }
        }
    }

    @Test fun retryAtEndRestartsTheSameItemFromZero() {
        val engine = RecordingPlayer(entries, state = Player.STATE_ENDED)
        PlaybackOperations.retry(engine.player)
        assertEquals(listOf("stop", "seek:2:0", "prepare", "play"), engine.operations)
        assertEquals("2", engine.currentId)
        assertEquals(0L, engine.position)
    }

    @Test fun retryWithoutAQueueDoesNotStartPlayback() {
        val engine = RecordingPlayer(emptyList(), playing = false)
        PlaybackOperations.retry(engine.player)
        assertTrue(engine.operations.isEmpty())
        assertFalse(engine.playing)
    }

    @Test fun shuffleAndRestoreOnlyMoveItemsWithoutReloadingCurrentPlayback() {
        for (playing in listOf(false, true)) {
            val engine = RecordingPlayer(entries, playing = playing)
            val current = engine.items[2]
            var ordered = entries
            repeat(20) { iteration ->
                ordered = QueuePolicy.reorder(ordered, "2", iteration % 2 == 0, kotlin.random.Random(iteration)).first
                PlaybackOperations.reorder(engine.player, ordered)
                assertEquals(ordered.map { it.track.id }, engine.items.map { it.mediaId })
                assertSame(current, engine.items[engine.index])
                assertEquals(12000L, engine.position)
                assertEquals(playing, engine.playing)
                assertEquals(Player.STATE_READY, engine.state)
            }
            assertEquals(entries.map { it.track.id }, engine.items.map { it.mediaId })
            assertTrue(engine.operations.isNotEmpty())
            assertTrue(engine.operations.all { it.startsWith("move:") })
        }
    }

    @Test fun unchangedAndEmptyOrdersDoNotIssuePlayerCommands() {
        val engine = RecordingPlayer(entries)
        PlaybackOperations.reorder(engine.player, entries)
        assertTrue(engine.operations.isEmpty())
        val empty = RecordingPlayer(emptyList())
        PlaybackOperations.reorder(empty.player, emptyList())
        assertTrue(empty.operations.isEmpty())
    }

    private class RecordingPlayer(entries: List<QueueEntry>, var state: Int = Player.STATE_READY, var playing: Boolean = true) {
        val items = entries.map { MediaItem.Builder().setMediaId(it.track.id).build() }.toMutableList()
        val operations = mutableListOf<String>()
        var index = if (items.isEmpty()) 0 else 2
        var position = 12000L
        val currentId get() = items.getOrNull(index)?.mediaId
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> index
                "getCurrentPosition" -> position
                "getPlaybackState" -> state
                "getMediaItemAt" -> items[args!![0] as Int]
                "stop" -> { operations += "stop"; state = Player.STATE_IDLE; null }
                "seekTo" -> {
                    index = args!![0] as Int; position = args[1] as Long
                    operations += "seek:$index:$position"; null
                }
                "prepare" -> {
                    assertEquals("Retry must first leave READY/BUFFERING", Player.STATE_IDLE, state)
                    operations += "prepare"; state = Player.STATE_BUFFERING; null
                }
                "play" -> { operations += "play"; playing = true; null }
                "moveMediaItem" -> {
                    val id = currentId
                    val from = args!![0] as Int; val to = args[1] as Int
                    items.add(to, items.removeAt(from)); index = items.indexOfFirst { it.mediaId == id }
                    operations += "move:$from:$to"; null
                }
                else -> error("Unexpected player operation: ${method.name}")
            }
        } as Player
    }
}
