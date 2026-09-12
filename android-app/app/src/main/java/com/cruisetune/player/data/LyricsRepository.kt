package com.cruisetune.player.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cruisetune.player.core.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Looks only beside the selected audio file, using its existing source authorization. */
class LyricsRepository(private val context: Context, private val library: LibraryRepository, private val client: OkHttpClient) {
    suspend fun load(track: Track): LrcLyrics? = withContext(Dispatchers.IO) {
        val source = library.database.sources().firstOrNull { it.id == track.sourceId }
            ?: throw UserError("音乐来源已移除")
        val name = track.relativePath.substringAfterLast('/').substringBeforeLast('.') + ".lrc"
        val bytes = if (source.kind == SourceKind.LOCAL) {
            val audio = Uri.parse(track.localUri)
            if (audio.scheme == "file") {
                val parent = File(requireNotNull(audio.path)).parentFile ?: return@withContext null
                val candidate = match(name, parent.listFiles()?.filter { it.isFile }.orEmpty(), File::getName)
                    ?: return@withContext null
                candidate.inputStream().use { readBounded(it) }
            } else {
                val uri = localSidecar(source, track, name) ?: return@withContext null
                context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                    ?: throw UserError("歌词文件无法读取")
            }
        } else {
            val provider = library.provider(source.kind, source.accountId)
            var parent = source.rootId
            for (part in directories(track)) {
                ensureActive()
                parent = provider.listChildren(parent).firstOrNull { it.isDirectory && it.name == part }?.id
                    ?: return@withContext null
            }
            val file = match(name, provider.listChildren(parent).filterNot { it.isDirectory }, RemoteEntry::name)
                ?: return@withContext null
            if (file.size > LrcParser.MAX_BYTES) throw UserError("歌词文件过大")
            val request = provider.resolve(file.id)
            readRemote(request)
        }
        ensureActive()
        LrcParser.parse(decode(bytes))
    }

    private fun directories(track: Track): List<String> = track.relativePath.split('/').dropLast(1).also { parts ->
        if (parts.size > 100 || parts.any { it.isEmpty() || it == "." || it == ".." }) throw UserError("歌词目录无法识别")
    }

    private data class Document(val uri: Uri, val name: String, val directory: Boolean)
    private fun localSidecar(source: MusicSource, track: Track, name: String): Uri? {
        val tree = Uri.parse(source.rootId)
        var parent = DocumentsContract.getTreeDocumentId(tree)
        fun children(id: String): List<Document> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Document(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                        cursor.getString(1).orEmpty(), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            } ?: throw UserError("歌词目录无法读取")
        }
        for (part in directories(track)) {
            val directory = children(parent).firstOrNull { it.directory && it.name == part } ?: return null
            parent = DocumentsContract.getDocumentId(directory.uri)
        }
        return match(name, children(parent).filterNot { it.directory }, Document::name)?.uri
    }

    private suspend fun readRemote(request: ReadRequest): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(request.url).apply { request.headers.forEach { (key, value) -> header(key, value) } }.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw UserError("歌词暂时无法下载，请稍后重试")
                        val body = it.body ?: throw UserError("歌词文件为空")
                        if (body.contentLength() > LrcParser.MAX_BYTES) throw UserError("歌词文件过大")
                        body.byteStream().use { stream -> readBounded(stream) }
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    companion object {
        internal fun <T> match(name: String, candidates: List<T>, filename: (T) -> String): T? {
            val exact=candidates.filter { filename(it) == name }
            return if(exact.isNotEmpty())exact.singleOrNull()
                else candidates.filter { filename(it).equals(name, ignoreCase = true) }.singleOrNull()
        }

        internal fun readBounded(input: InputStream): ByteArray {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > LrcParser.MAX_BYTES) throw UserError("歌词文件过大")
                result.write(buffer, 0, count)
            }
            return result.toByteArray()
        }

        internal fun decode(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (_: CharacterCodingException) { String(bytes, Charset.forName("GB18030")) }
        }
    }
}
