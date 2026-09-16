package com.cruisetune.player.data

import android.app.Application
import com.cruisetune.player.core.*
import com.cruisetune.player.data.open.OpenConnections
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33], application = Application::class, manifest = Config.NONE)
class LibraryMaintenanceTest {
    private lateinit var app: Application
    private lateinit var database: LibraryDatabase
    private val source = MusicSource("source", SourceKind.QUARK, "示例目录", "0", "test-account")
    private fun track(id: String, sourceId: String = source.id) = Track(id, sourceId, id, "示例$id", size = 100, version = "1")
    private fun snapshot(revision: Long, ids: List<String>, position: Long = 0) = PlaybackSnapshot(revision, ids.mapIndexed { i, id -> QueueEntry(track(id), i) }, 0, position, true)
    private fun count(table: String, condition: String = "1=1") = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $condition", null).use { it.moveToFirst(); it.getInt(0) }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication(); app.deleteDatabase("cruise-library.db")
        database = LibraryDatabase(app); database.saveSource(source)
    }
    @After fun cleanup() { database.close(); app.deleteDatabase("cruise-library.db") }

    @Test fun repeatedScansRetireMissingRowsAndDoNotTouchOtherSources() {
        database.replaceScan("other", listOf(track("other-track", "other")))
        repeat(12) { round ->
            database.replaceScan(source.id, (0 until 40).map { track("$round-$it") })
            assertEquals(41, count("tracks"))
        }
        database.replaceScan(source.id, emptyList())
        assertEquals(listOf("other-track"), database.tracks().map { it.id })
    }

    @Test fun failedScanRollsBackAndUnknownReferencesDeferCleanup() {
        database.replaceScan(source.id, listOf(track("old")))
        assertTrue(runCatching { database.replaceScan(source.id, listOf(track("new"), track("wrong", "other"))) }.isFailure)
        assertEquals(listOf("old"), database.tracks().map { it.id })
        database.replaceScan(source.id, listOf(track("new")), null)
        assertEquals(2, count("tracks"))
        database.pruneMissingTracks(emptySet())
        assertEquals(1, count("tracks"))
    }

    @Test fun cleanupPreservesBothRecoveryQueuesAndRetiresDisplacedRevision() {
        database.replaceScan(source.id, listOf(track("old"), track("new")))
        database.saveQueue(snapshot(1, listOf("old"), 1234))
        database.saveQueue(snapshot(2, listOf("new"), 5678))
        database.replaceScan(source.id, emptyList())
        assertEquals(2, count("tracks"))
        assertEquals(2, count("queue_items"))
        database.writableDatabase.execSQL("UPDATE queue_items SET payload='damaged' WHERE revision=2")
        assertEquals("old", database.restore().entries.single().track.id)
        database.writableDatabase.execSQL("UPDATE queue_items SET payload=? WHERE revision=2", arrayOf(JsonCodec.encode(track("new"))))
        database.savePosition(snapshot(2, listOf("new"), 6000))
        database.pruneMissingTracks(emptySet())
        assertEquals(1, count("queue_items"))
        assertEquals(1, count("tracks"))
        database.close(); database = LibraryDatabase(app)
        assertEquals("new", database.restore().entries.single().track.id)
        assertEquals(6000, database.restore().positionMs)
    }

    @Test fun largeRetentionSetAndBatchedDeletionDoNotAccumulateRows() {
        val ids = (0 until 1400).map { "offline-$it" }
        database.replaceScan(source.id, ids.map { track(it) } + track("discard"))
        database.replaceScan(source.id, emptyList(), ids.toSet())
        assertEquals(1400, count("tracks"))
        assertNotNull(database.findTrack("offline-1000"))
        database.pruneMissingTracks(setOf("offline-1000"))
        assertEquals(1, count("tracks"))
        database.pruneMissingTracks(emptySet())
        assertEquals(0, count("tracks"))
    }

    @Test fun legacyMissingRowsAreRemovedAfterReopenWithoutSchemaReset() {
        database.replaceScan(source.id, listOf(track("live"), track("legacy")))
        database.writableDatabase.execSQL("UPDATE tracks SET present=0 WHERE id='legacy'")
        database.close(); database = LibraryDatabase(app)
        database.pruneMissingTracks(emptySet())
        assertNull(database.findTrack("legacy"))
        assertEquals("live", database.tracks().single().id)
        assertEquals(source, database.sources().single())
    }

    @Test fun writerJournalPolicyAndFrequentProgressRemainBounded() {
        val saved = snapshot(1, listOf("track"))
        database.saveQueue(saved)
        repeat(350) { database.savePosition(saved.copy(positionMs = it * 2000L)) }
        assertEquals(2, count("checkpoints"))
        assertEquals(1, count("queue_items"))
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            fun pragma(name: String) = db.rawQuery("PRAGMA $name", null).use { it.moveToFirst(); it.getInt(0) }
            assertEquals(2, pragma("synchronous"))
            assertEquals(LibraryDatabase.WAL_CHECKPOINT_PAGES, pragma("wal_autocheckpoint"))
            assertEquals(LibraryDatabase.WAL_RETAIN_BYTES, pragma("journal_size_limit"))
        } finally { db.endTransaction() }
        assertEquals(698000, database.restore().positionMs)
    }

    @Test fun walRecyclesAfterLargeCommittedScan() {
        val large = (0 until 1500).map { track("large-$it").copy(title = "x".repeat(1500)) }
        database.replaceScan(source.id, large)
        database.saveSource(source.copy(title = "再次保存"))
        database.saveSource(source)
        val mode = database.readableDatabase.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst(); it.getString(0) }
        assertEquals("wal", mode.lowercase())
        val wal = File(app.getDatabasePath("cruise-library.db").path + "-wal")
        assertTrue("A real file-backed WAL is required for this test", wal.exists())
        assertTrue("WAL should be recycled after checkpoint and the next write", wal.length() <= LibraryDatabase.WAL_RETAIN_BYTES)
        assertEquals(1500, database.tracks().size)
    }

    @Test fun repositoryRetainsVisibleRowsAndDefersCleanupIfOfflineIndexFails() = runBlocking {
        val vault = CredentialVault(app); val http = OkHttpClient()
        var indexAvailable = true
        val repository = LibraryRepository(app, database, vault, http, OpenConnections(app, vault, http, http),
            { if (!indexAvailable) throw IOException("test index unavailable") else emptySet() })
        database.replaceScan(source.id, listOf(track("visible")))
        repository.reload()
        database.replaceScan(source.id, listOf(track("replacement")), null)
        repository.reload()
        assertEquals(2, count("tracks")) // The outgoing UI generation is still protected.
        indexAvailable = false
        repository.reload()
        assertEquals(2, count("tracks"))
        indexAvailable = true
        repository.reload()
        assertEquals(1, count("tracks"))
        assertEquals("replacement", repository.state.value.tracks.single().id)
    }
}
