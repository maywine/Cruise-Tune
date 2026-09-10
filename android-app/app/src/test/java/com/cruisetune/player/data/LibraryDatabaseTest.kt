package com.cruisetune.player.data

import android.app.Application
import org.robolectric.RuntimeEnvironment
import com.cruisetune.player.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LibraryDatabaseTest {
    private lateinit var app: Application
    private lateinit var database: LibraryDatabase
    private val source = MusicSource("source", SourceKind.QUARK, "音乐", "0", "account")
    private fun track(id: String) = Track(id, "source", id, "歌曲$id", size = 100, version = "1")
    private fun snapshot(revision: Long, tracks: List<Track>, position: Long = 0) = PlaybackSnapshot(revision, tracks.mapIndexed { i, t -> QueueEntry(t, i) }, 0, position, true, 2, true)
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication(); app.deleteDatabase("cruise-library.db")
        database = LibraryDatabase(app); database.saveSource(source)
    }
    @After fun tearDown() { database.close(); app.deleteDatabase("cruise-library.db") }
    @Test fun restoredAfterDatabaseReopenPreservesPositionOrderAndModes() {
        val s = snapshot(1, listOf(track("b"), track("a")), 75000)
        database.saveQueue(s); database.savePosition(s.copy(positionMs = 82000))
        database.close(); database = LibraryDatabase(app)
        val restored = database.restore()
        assertEquals(82000, restored.positionMs); assertEquals(listOf("b", "a"), restored.entries.map { it.track.id })
        assertTrue(restored.playIntent); assertTrue(restored.shuffled); assertEquals(2, restored.repeatMode)
        database.readableDatabase.rawQuery("PRAGMA synchronous", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
    }
    @Test fun stalePlaybackEventCannotOverwriteNewQueueCheckpoint() {
        val old = snapshot(1, listOf(track("old")), 1000)
        database.saveQueue(old); database.saveQueue(snapshot(2, listOf(track("new")), 2000))
        database.savePosition(old.copy(positionMs = 3000))
        assertEquals("new", database.restore().entries.first().track.id); assertEquals(2000, database.restore().positionMs)
    }
    @Test fun incompleteReplacementTransactionRollsBackWithoutLosingLibrary() {
        database.replaceScan("source", listOf(track("old")))
        assertTrue(runCatching { database.replaceScan("source", listOf(track("new"), track("bad").copy(sourceId = "wrong-source"))) }.isFailure)
        assertEquals(listOf("old"), database.tracks().map { it.id })
    }
    @Test fun scanChangesDoNotReorderOrEraseCurrentQueue() {
        database.replaceScan("source", listOf(track("a"), track("b")))
        database.saveQueue(snapshot(1, listOf(track("b"), track("a")), 32000))
        database.replaceScan("source", listOf(track("c")))
        assertEquals(listOf("c"), database.tracks().map { it.id })
        assertEquals(listOf("b", "a"), database.restore().entries.map { it.track.id })
        assertNotNull(database.findTrack("b"))
    }
    @Test fun damagedLatestQueueFallsBackToPreviousValidRevision() {
        database.saveQueue(snapshot(1, listOf(track("safe")), 22000))
        database.saveQueue(snapshot(2, listOf(track("latest")), 44000))
        database.writableDatabase.execSQL("UPDATE queue_items SET payload='corrupt' WHERE revision=2")
        val restored = database.restore()
        assertEquals("safe", restored.entries[0].track.id); assertEquals(22000, restored.positionMs)
    }
}
