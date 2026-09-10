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

class LibraryRepository(private val context: Context, val database: LibraryDatabase, val vault: CredentialVault, val client: OkHttpClient, private val openConnections: com.cruisetune.player.data.open.OpenConnections) {
    private val stateMutable = MutableStateFlow(LibraryState())
    val state = stateMutable.asStateFlow()
    private val scanLock = Mutex()
    fun quark(accountId: String) = QuarkApi(client, { vault.get(accountId) }, { vault.put(accountId, it) })
    fun provider(kind: SourceKind, accountId: String): MusicProvider = when (kind) {
        SourceKind.QUARK -> quark(accountId)
        SourceKind.QUARK_OPEN -> openConnections.provider(accountId)
        SourceKind.LOCAL -> throw IllegalArgumentException("Local folders use the document provider")
    }
    suspend fun reload(message: String? = null) = withContext(Dispatchers.IO) {
        stateMutable.value = stateMutable.value.copy(sources = database.sources(), tracks = database.tracks(), message = message)
    }
    suspend fun addAndScan(source: MusicSource) {
        withContext(Dispatchers.IO) { database.saveSource(source) }
        reload()
        scan(source)
    }
    suspend fun scan(source: MusicSource) = scanLock.withLock {
        stateMutable.value = stateMutable.value.copy(scanning = source.id, scannedCount = 0, message = null)
        try {
            val tracks = withContext(Dispatchers.IO) {
                if (source.kind != SourceKind.LOCAL) scanQuark(source) else scanLocal(source)
            }
            withContext(Dispatchers.IO) { database.replaceScan(source.id, tracks) }
            reload("已更新 ${tracks.size} 首音乐")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reload(readableError(e)) }
        finally { stateMutable.value = stateMutable.value.copy(scanning = null) }
    }
    private suspend fun scanQuark(source: MusicSource): List<Track> {
        val api = provider(source.kind, source.accountId)
        val queue = ArrayDeque<Pair<String, String>>().apply { add(source.rootId to "") }
        val visited = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (id, prefix) = queue.removeFirst()
            if (!visited.add(id)) continue
            if (visited.size > 5000) throw UserError("目录层级过多，请选择更小的音乐目录")
            api.listChildren(id).forEach { file ->
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
