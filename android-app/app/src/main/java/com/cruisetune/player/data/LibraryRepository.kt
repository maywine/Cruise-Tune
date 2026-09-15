package com.cruisetune.player.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.cruisetune.player.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.ArrayDeque

data class LibraryState(val sources: List<MusicSource> = emptyList(), val tracks: List<Track> = emptyList(), val scanning: String? = null, val scannedCount: Int = 0, val message: String? = null)

class LibraryRepository(private val context: Context, val database: LibraryDatabase, val vault: CredentialVault, val client: OkHttpClient, private val openConnections: com.cruisetune.player.data.open.OpenConnections,
    private val retainedTrackIds: () -> Set<String> = { emptySet() },
    private val providerFactory: ((SourceKind, String) -> MusicProvider)? = null) {
    private val stateMutable = MutableStateFlow(LibraryState())
    val state = stateMutable.asStateFlow()
    private val scanLock = Mutex()
    fun quark(accountId: String) = QuarkApi(client, { vault.get(accountId) }, { vault.put(accountId, it) },
        cookieLock = vault, cookieRevision = { vault.revision(accountId) })
    suspend fun connectWebSession(value: String, reconnect: String?, id: String) = scanLock.withLock {
        withContext(Dispatchers.IO) {
            val sources = if (reconnect == null) emptyList() else database.sources().filter {
                it.kind == SourceKind.QUARK && it.accountId == reconnect
            }
            val probes = sources.mapNotNull { database.tracks(it.id).firstOrNull()?.fileId }
            // Validation uses an isolated cookie. Neither failed login nor cancellation replaces
            // the existing session, source IDs, queue, position or cache identity.
            QuarkWebSession.connect(value, sources.map { it.rootId }, probes,
                { read, save -> QuarkApi(client, read, save) }, { vault.put(id, it) })
        }
    }
    fun provider(kind: SourceKind, accountId: String): MusicProvider = providerFactory?.invoke(kind, accountId) ?: when (kind) {
        SourceKind.QUARK -> quark(accountId)
        SourceKind.QUARK_OPEN -> openConnections.provider(accountId)
        SourceKind.LOCAL -> throw IllegalArgumentException("Local folders use the document provider")
    }
    suspend fun reload(message: String? = null) = withContext(Dispatchers.IO) {
        retentionSnapshot()?.let { database.pruneMissingTracks(it) }
        stateMutable.value = stateMutable.value.copy(sources = database.sources(), tracks = database.tracks(), message = message)
    }
    suspend fun addAndScan(source: MusicSource, directoryPath: String = "", confirmedExistingId: String? = null, independent: Boolean = false,
        initialChildren: List<RemoteEntry>? = null): MusicSource = scanLock.withLock {
        val existing = withContext(Dispatchers.IO) {
            database.existingSource(source) ?: confirmedExistingId?.let { id -> database.sources().firstOrNull { it.id == id } }
        }
        if (existing != null) {
            val sameAccess = existing.kind == source.kind && existing.accountId == source.accountId
            val active = if (sameAccess) existing.copy(title = source.title, rootId = source.rootId, recursive = source.recursive) else existing
            withContext(Dispatchers.IO) {
                if (active != existing) database.saveSource(active)
                database.registerAccess(source, existing.id, directoryPath)
            }
            if (active.recursive != existing.recursive) {
                scanLocked(active, initialChildren)
                return@withLock active
            }
            reload("该目录已添加，已使用现有曲库")
            return@withLock active
        }
        check(confirmedExistingId == null) { "原目录已移除，请重新选择" }
        if (!independent && withContext(Dispatchers.IO) { database.matchingSourcePaths(source, directoryPath).isNotEmpty() })
            throw UserError("该目录可能已添加，请先确认是否为同一账号的目录")
        withContext(Dispatchers.IO) {
            database.saveSource(source)
            database.registerAccess(source, source.id, directoryPath)
        }
        reload()
        scanLocked(source, initialChildren)
        source
    }
    suspend fun removeSources(ids: Set<String>, remaining: PlaybackSnapshot) = scanLock.withLock {
        withContext(Dispatchers.IO) { database.removeSources(ids, remaining) }
        reload("已移除本机音乐来源")
    }
    suspend fun scan(source: MusicSource) = scanLock.withLock { scanLocked(source) }
    private suspend fun scanLocked(source: MusicSource, initialChildren: List<RemoteEntry>? = null) {
        if (withContext(Dispatchers.IO) { database.sources().none { it.id == source.id } }) return
        stateMutable.value = stateMutable.value.copy(scanning = source.id, scannedCount = 0, message = null)
        try {
            val tracks = withContext(Dispatchers.IO) {
                if (source.kind != SourceKind.LOCAL) scanQuark(source, initialChildren) else scanLocal(source)
            }
            withContext(Dispatchers.IO) { database.replaceScan(source.id, tracks, retentionSnapshot()) }
            reload("已更新 ${tracks.size} 首音乐")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reload(readableError(e)) }
        finally { stateMutable.value = stateMutable.value.copy(scanning = null) }
    }
    private suspend fun scanQuark(source: MusicSource, initialChildren: List<RemoteEntry>? = null): List<Track> {
        val api = provider(source.kind, source.accountId)
        val queue = ArrayDeque<Pair<String, String>>().apply { add(source.rootId to "") }
        val visited = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (id, prefix) = queue.removeFirst()
            if (!visited.add(id)) continue
            if (visited.size > 5000) throw UserError("目录层级过多，请选择更小的音乐目录")
            (if (id == source.rootId && initialChildren != null) initialChildren else api.listChildren(id)).forEach { file ->
                if (file.isDirectory) { if (source.recursive) queue.add(file.id to "$prefix${file.name}/") }
                else AudioFiles.mime(file.name)?.let { mime ->
                    val identity = if (source.kind == SourceKind.QUARK_OPEN) com.cruisetune.player.data.open.openFileIdentity(file.id) else file.id
                    tracks += Track(stableHash(source.id, identity), source.id, file.id, AudioFiles.displayName(file.name), source.title, prefix + file.name, file.size, file.modified, mimeType = mime, contentIdentity = if (source.kind == SourceKind.QUARK_OPEN) identity else "")
                }
            }
            checkTrackLimit(tracks.size)
            stateMutable.value = stateMutable.value.copy(scannedCount = tracks.size)
        }
        return tracks
    }
    private suspend fun scanLocal(source: MusicSource): List<Track> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(source.rootId)) ?: throw UserError("目录无法访问，请重新选择")
        if (!root.canRead()) throw UserError("目录访问权限已失效，请重新选择")
        val queue = ArrayDeque<Pair<DocumentFile, String>>().apply { add(root to "") }
        val visited = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory, prefix) = queue.removeFirst()
            if (!visited.add(directory.uri.toString())) continue
            if (visited.size > 5000) throw UserError("目录层级过多，请选择更小的目录")
            if (!directory.canRead()) throw UserError("部分子目录无法访问，已保留原列表")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(source.rootId), DocumentsContract.getDocumentId(directory.uri))
            val files = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                buildList<DocumentFile> {
                    while (cursor.moveToNext()) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(source.rootId), cursor.getString(0))
                        add(DocumentFile.fromSingleUri(context, uri) ?: throw UserError("部分文件无法读取，已保留原列表"))
                    }
                }
            } ?: throw UserError("目录读取未完成，已保留原列表")
            files.forEach { file ->
                val name = file.name ?: return@forEach
                if (file.isDirectory) { if (source.recursive) queue.add(file to "$prefix$name/") }
                else AudioFiles.mime(name)?.let { mime ->
                    val uri = file.uri.toString()
                    tracks += Track(stableHash(source.id, uri), source.id, uri, AudioFiles.displayName(name), source.title, prefix + name, file.length(), file.lastModified().toString(), uri, mime)
                }
            }
            checkTrackLimit(tracks.size)
            stateMutable.value = stateMutable.value.copy(scannedCount = tracks.size)
        }
        return tracks
    }
    private fun checkTrackLimit(count: Int) { if (count > 100000) throw UserError("歌曲数量过多，请选择更小的目录") }
    private fun retentionSnapshot(): Set<String>? = try {
        // A visible row can still be tapped while scan results are being committed. Keep that
        // display generation until a later maintenance pass sees the replacement library.
        retainedTrackIds() + stateMutable.value.tracks.map { it.id }
    }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null } // If offline references cannot be read, preserve old metadata.
    fun findTrack(id: String) = database.findTrack(id)
    suspend fun readRequest(track: Track): ReadRequest {
        if (track.localUri.isNotBlank()) return ReadRequest(track.localUri, emptyMap())
        val source = withContext(Dispatchers.IO) { database.sources().find { it.id == track.sourceId } }
            ?: throw UserError("音乐来源不可用，请重新添加目录")
        return provider(source.kind, source.accountId).resolve(track.fileId)
    }
    suspend fun onReadAuthFailure(track: Track, request: ReadRequest) {
        val source = withContext(Dispatchers.IO) { database.sources().find { it.id == track.sourceId } } ?: return
        if (source.kind == SourceKind.QUARK_OPEN && request.credentialGeneration != null) openConnections.sessions.refreshAfterFailure(source.accountId, request.credentialGeneration)
    }
}
