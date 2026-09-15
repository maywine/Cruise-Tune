package com.cruisetune.player.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import com.cruisetune.player.core.*

class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "cruise-library.db", null, 2) {
    companion object {
        const val WAL_CHECKPOINT_PAGES = 256
        const val WAL_RETAIN_BYTES = 1024 * 1024
        private const val CHECKPOINT_INTERVAL_MS = 60_000L
    }
    private val nextCheckpointAt = AtomicLong(0)
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, root TEXT NOT NULL, account TEXT NOT NULL, recursive INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tracks(id TEXT PRIMARY KEY, source_id TEXT NOT NULL, payload TEXT NOT NULL, present INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE INDEX tracks_source ON tracks(source_id,present)")
        db.execSQL("CREATE TABLE queue_items(revision INTEGER NOT NULL, ordinal INTEGER NOT NULL, original INTEGER NOT NULL, track_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(revision,ordinal))")
        db.execSQL("CREATE INDEX queue_tracks ON queue_items(track_id)")
        db.execSQL("CREATE TABLE checkpoints(id INTEGER PRIMARY KEY, revision INTEGER NOT NULL, item_index INTEGER NOT NULL, position INTEGER NOT NULL, intent INTEGER NOT NULL, repeat_mode INTEGER NOT NULL, shuffled INTEGER NOT NULL)")
        createSourceRegistry(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createSourceRegistry(db) }
    private fun createSourceRegistry(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE source_paths(source_id TEXT PRIMARY KEY, path TEXT NOT NULL)")
        db.execSQL("CREATE TABLE source_access(kind TEXT NOT NULL, account TEXT NOT NULL, root_key TEXT NOT NULL, source_id TEXT NOT NULL, PRIMARY KEY(kind,account,root_key))")
    }
    private fun rootKey(source: MusicSource) = if (source.kind == SourceKind.QUARK_OPEN)
        com.cruisetune.player.data.open.openFileIdentity(source.rootId) else source.rootId
    fun existingSource(access: MusicSource): MusicSource? {
        val all = sources()
        val linked = readableDatabase.rawQuery("SELECT source_id FROM source_access WHERE kind=? AND account=? AND root_key=?",
            arrayOf(access.kind.name, access.accountId, rootKey(access))).use { if (it.moveToFirst()) it.getString(0) else null }
        return all.firstOrNull { it.id == linked } ?: all.firstOrNull {
            it.kind == access.kind && it.accountId == access.accountId && rootKey(it) == rootKey(access)
        }
    }
    fun matchingSourcePaths(access: MusicSource, path: String): List<MusicSource> {
        if (path.isBlank() || access.kind == SourceKind.LOCAL) return emptyList()
        val paths = readableDatabase.rawQuery("SELECT source_id,path FROM source_paths", null).use { c ->
            buildMap { while(c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
        // These are candidates for explicit user confirmation, never evidence of account identity.
        return sources().filter { it.kind != SourceKind.LOCAL && it.kind != access.kind &&
            (paths[it.id] == path || it.title == access.title) }
    }
    fun registerAccess(access: MusicSource, existingId: String, path: String) = transaction { db ->
        check(sources().any { it.id == existingId })
        db.insertWithOnConflict("source_access", null, ContentValues().apply {
            put("kind",access.kind.name);put("account",access.accountId);put("root_key",rootKey(access));put("source_id",existingId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (path.isNotBlank()) db.insertWithOnConflict("source_paths",null,ContentValues().apply {
            put("source_id",existingId);put("path",path)
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun forgetAccess(kind: SourceKind, account: String) = transaction { db ->
        db.delete("source_access","kind=? AND account=?",arrayOf(kind.name,account))
    }

    fun sources(): List<MusicSource> = readableDatabase.rawQuery("SELECT * FROM sources ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(MusicSource(c.getString(0), SourceKind.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) == 1)) }
    }
    fun saveSource(source: MusicSource) = transaction { db ->
        db.insertWithOnConflict("sources", null, ContentValues().apply {
            put("id", source.id); put("kind", source.kind.name); put("title", source.title)
            put("root", source.rootId); put("account", source.accountId); put("recursive", if (source.recursive) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun tracks(sourceId: String? = null): List<Track> {
        val where = if (sourceId == null) "present=1" else "present=1 AND source_id=?"
        val args = sourceId?.let { arrayOf(it) }
        return readableDatabase.rawQuery("SELECT payload FROM tracks WHERE $where", args).use { c ->
            buildList { while (c.moveToNext()) runCatching { JsonCodec.decode(c.getString(0)) }.getOrNull()?.let { add(it) } }
        }.sortedWith { a, b -> NaturalOrder.compare(a.relativePath, b.relativePath) }
    }
    fun findTrack(id: String): Track? {
        readableDatabase.rawQuery("SELECT payload FROM tracks WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) return runCatching { JsonCodec.decode(c.getString(0)) }.getOrNull()
        }
        return readableDatabase.rawQuery("SELECT payload FROM queue_items WHERE track_id=? ORDER BY revision DESC LIMIT 1", arrayOf(id)).use { c ->
            if (c.moveToFirst()) runCatching { JsonCodec.decode(c.getString(0)) }.getOrNull() else null
        }
    }
    fun trackIdsForSources(ids: Set<String>): Set<String> = buildSet {
        ids.forEach { source -> readableDatabase.rawQuery("SELECT id FROM tracks WHERE source_id=?", arrayOf(source)).use { c ->
            while(c.moveToNext()) add(c.getString(0))
        } }
    }
    /** Old queue versions may still have a complete cache after a newer directory scan. */
    fun findTrackForCache(id: String, key: String): Track? {
        findTrack(id)?.takeIf { it.cacheKey == key }?.let { return it }
        return readableDatabase.rawQuery("SELECT payload FROM queue_items WHERE track_id=? ORDER BY revision DESC", arrayOf(id)).use { c ->
            while (c.moveToNext()) {
                val track = runCatching { JsonCodec.decode(c.getString(0)) }.getOrNull()
                if (track?.cacheKey == key) return@use track
            }
            null
        }
    }
    fun replaceScan(sourceId: String, found: List<Track>, retainedTrackIds: Set<String>? = emptySet()) = transaction { db ->
        db.execSQL("UPDATE tracks SET present=0 WHERE source_id=?", arrayOf(sourceId))
        found.distinctBy { it.id }.forEach { track ->
            require(track.sourceId == sourceId)
            db.insertWithOnConflict("tracks", null, ContentValues().apply {
                put("id", track.id); put("source_id", sourceId); put("payload", JsonCodec.encode(track)); put("present", 1)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        // A null retention snapshot means its provider failed: defer cleanup, not the scan.
        if (retainedTrackIds != null) pruneMissingTracks(db, retainedTrackIds, sourceId)
    }
    fun pruneMissingTracks(retainedTrackIds: Set<String>) = transaction { db ->
        pruneQueues(db)
        pruneMissingTracks(db, retainedTrackIds, null)
    }
    fun saveQueue(snapshot: PlaybackSnapshot) = transaction { db ->
        require(snapshot.entries.isNotEmpty())
        backupCheckpoint(db)
        snapshot.entries.forEachIndexed { index, entry ->
            db.insertOrThrow("queue_items", null, ContentValues().apply {
                put("revision", snapshot.revision); put("ordinal", index); put("original", entry.originalIndex)
                put("track_id", entry.track.id); put("payload", JsonCodec.encode(entry.track))
            })
        }
        writeCheckpoint(db, snapshot)
        pruneQueues(db)
    }
    fun savePosition(snapshot: PlaybackSnapshot) = transaction { db ->
        val current = db.rawQuery("SELECT revision FROM checkpoints WHERE id=1", null).use { if (it.moveToFirst()) it.getLong(0) else -1 }
        if (current == snapshot.revision) {
            val oldBackup = db.rawQuery("SELECT revision FROM checkpoints WHERE id=2", null).use { if (it.moveToFirst()) it.getLong(0) else null }
            backupCheckpoint(db); writeCheckpoint(db, snapshot)
            // Do not scan a potentially large queue every two seconds. Retire only the displaced revision.
            if (oldBackup != null && oldBackup != snapshot.revision) {
                db.execSQL("DELETE FROM queue_items WHERE revision=? AND revision NOT IN (SELECT revision FROM checkpoints)", arrayOf(oldBackup))
            }
        }
    }
    fun removeSources(ids: Set<String>, remaining: PlaybackSnapshot) = transaction { db ->
        require(remaining.entries.none { it.track.sourceId in ids })
        // Retire both snapshots so an old backup cannot restore a removed source.
        db.delete("queue_items", null, null); db.delete("checkpoints", null, null)
        ids.forEach { id ->
            db.delete("tracks", "source_id=?", arrayOf(id))
            db.delete("sources", "id=?", arrayOf(id))
            db.delete("source_paths", "source_id=?", arrayOf(id))
            db.delete("source_access", "source_id=?", arrayOf(id))
        }
        remaining.entries.forEachIndexed { index, entry ->
            db.insertOrThrow("queue_items", null, ContentValues().apply {
                put("revision", remaining.revision); put("ordinal", index); put("original", entry.originalIndex)
                put("track_id", entry.track.id); put("payload", JsonCodec.encode(entry.track))
            })
        }
        if (remaining.entries.isNotEmpty()) writeCheckpoint(db, remaining)
    }
    fun restore(): PlaybackSnapshot = readableDatabase.rawQuery("SELECT revision,item_index,position,intent,repeat_mode,shuffled FROM checkpoints ORDER BY id", null).use { c ->
        while (c.moveToNext()) {
            val revision = c.getLong(0)
            val entries = runCatching {
                readableDatabase.rawQuery("SELECT payload,original FROM queue_items WHERE revision=? ORDER BY ordinal", arrayOf(revision.toString())).use { q ->
                    buildList { while (q.moveToNext()) add(QueueEntry(JsonCodec.decode(q.getString(0)), q.getInt(1))) }
                }
            }.getOrNull() ?: continue
            if (entries.isEmpty()) continue
            val index = c.getInt(1).coerceIn(entries.indices)
            return PlaybackSnapshot(revision, entries, index, QueuePolicy.clampPosition(c.getLong(2), entries[index].track.durationMs), c.getInt(3) == 1, c.getInt(4).coerceIn(0, 2), c.getInt(5) == 1)
        }
        PlaybackSnapshot()
    }
    private fun backupCheckpoint(db: SQLiteDatabase) {
        db.execSQL("INSERT OR REPLACE INTO checkpoints SELECT 2,revision,item_index,position,intent,repeat_mode,shuffled FROM checkpoints WHERE id=1")
    }
    private fun writeCheckpoint(db: SQLiteDatabase, s: PlaybackSnapshot) {
        db.insertWithOnConflict("checkpoints", null, ContentValues().apply {
            put("id", 1); put("revision", s.revision); put("item_index", s.index.coerceAtLeast(0)); put("position", s.positionMs.coerceAtLeast(0))
            put("intent", if (s.playIntent) 1 else 0); put("repeat_mode", s.repeatMode); put("shuffled", if (s.shuffled) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun pruneQueues(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM queue_items WHERE revision NOT IN (SELECT revision FROM checkpoints)")
    }
    private fun pruneMissingTracks(db: SQLiteDatabase, retained: Set<String>, sourceId: String?) {
        // Keyset batches bound memory for legacy databases and avoid SQLite's parameter limit.
        // Close each read cursor before deleting; never mutate a table while iterating its cursor.
        var after: String? = null
        db.compileStatement("DELETE FROM tracks WHERE id=? AND present=0").use { delete ->
            while (true) {
                val clauses = mutableListOf("present=0", "NOT EXISTS (SELECT 1 FROM queue_items WHERE track_id=tracks.id)")
                val args = mutableListOf<String>()
                sourceId?.let { clauses += "source_id=?"; args += it }
                after?.let { clauses += "id>?"; args += it }
                val stale = db.rawQuery("SELECT id FROM tracks WHERE ${clauses.joinToString(" AND ")} ORDER BY id LIMIT 256", args.toTypedArray()).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                if (stale.isEmpty()) break
                stale.filterNot(retained::contains).forEach { delete.bindString(1, it); delete.executeUpdateDelete() }
                after = stale.last()
            }
        }
    }
    private fun configureWriterJournal(db: SQLiteDatabase) {
        // These PRAGMAs return rows. Run inside the transaction so Android's WAL pool cannot
        // route them to a read-only connection. Reapply for replaced connections on older APIs.
        db.rawQuery("PRAGMA wal_autocheckpoint=$WAL_CHECKPOINT_PAGES", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA journal_size_limit=$WAL_RETAIN_BYTES", null).use { it.moveToFirst() }
    }
    private fun checkpointIfDue(db: SQLiteDatabase) {
        val now = SystemClock.elapsedRealtime()
        val due = nextCheckpointAt.get()
        if (now < due || !nextCheckpointAt.compareAndSet(due, now + CHECKPOINT_INTERVAL_MS)) return
        try {
            // Never wait for a reader or writer, and never delete a WAL file by hand.
            db.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { it.moveToFirst() }
        } catch (_: SQLiteException) {
            // The data transaction has already committed durably. Retry maintenance later.
        }
    }
    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { configureWriterJournal(db); block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        checkpointIfDue(db)
    }
}
