package com.cruisetune.player.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Converts the Media3 artwork bytes into the address understood by the car receiver.
 * API 28 follows the reviewed implementation and exposes a file URI; newer Android versions
 * use a tiny loopback server so the receiver never receives an app-private file path.
 */
internal class DashboardCoverStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    data class Prepared(val trackId: String, val uri: String, val sourceKey: String)

    private val appContext = context.applicationContext
    private val directory = (appContext.getExternalFilesDir(null)?.let { File(it, DIR) }
        ?: File(appContext.filesDir, DIR)).also { it.mkdirs() }
    private val serverLock = Any()
    private var server: LoopbackCoverServer? = null
    private var serverGeneration = 0L
    private var closed = false

    init {
        cleanupTemporaryFiles()
    }

    suspend fun prepare(trackId: String, artwork: ByteArray, sourceKey: String): Prepared? = withContext(Dispatchers.IO) {
        ensureActive()
        if (artwork.isEmpty() || artwork.size > MAX_INPUT_BYTES) return@withContext null
        val generation = synchronized(serverLock) { serverGeneration.takeUnless { closed } }
            ?: return@withContext null
        val bitmap = decode(artwork) ?: return@withContext null
        try {
            val jpeg = encode(bitmap) ?: return@withContext null
            val digest = sha256(jpeg).take(16)
            val file = File(directory, "cover_${digest}.jpg")
            if (!file.exists() || file.length() != jpeg.size.toLong()) {
                val temporary = File(directory, ".${file.name}.${System.nanoTime()}.tmp")
                var moved = false
                try {
                    temporary.outputStream().use { it.write(jpeg) }
                    check(temporary.length() == jpeg.size.toLong())
                    if (!temporary.renameTo(file)) return@withContext null
                    moved = true
                } finally {
                    if (!moved) temporary.delete()
                }
            }
            ensureActive()
            val stillCurrent = synchronized(serverLock) { !closed && generation == serverGeneration }
            if (!stillCurrent) return@withContext null
            val uri = if (Build.VERSION.SDK_INT >= 29) {
                val local = loopbackServer(generation) ?: return@withContext null
                local.url(file.name, digest)
            } else {
                "file://${file.absolutePath}"
            }
            cleanup(file)
            Prepared(trackId, uri, sourceKey)
        } finally {
            bitmap.recycle()
        }
    }

    /** Stops serving covers while allowing a later enable to start a fresh server. */
    fun stop() {
        synchronized(serverLock) {
            serverGeneration++
            server?.stop()
            server = null
        }
    }

    fun close() {
        synchronized(serverLock) {
            closed = true
            serverGeneration++
            server?.stop()
            server = null
        }
    }

    private fun loopbackServer(generation: Long): LoopbackCoverServer? = synchronized(serverLock) {
        if (closed || generation != serverGeneration) return@synchronized null
        server?.let { return@synchronized it }
        val created = LoopbackCoverServer(directory, scope)
        if (!created.start()) return@synchronized null
        if (closed || generation != serverGeneration) {
            created.stop()
            return@synchronized null
        }
        server = created
        created
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        var quality = 88
        repeat(4) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_OUTPUT_BYTES || quality <= 50) return bytes
            quality -= 12
        }
        return null
    }

    private fun cleanup(current: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.startsWith("cover_") && it != current }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(MAX_FILES - 1).forEach { it.delete() }
        cleanupTemporaryFiles()
    }

    private fun cleanupTemporaryFiles() {
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(".cover_") && it.name.endsWith(".tmp") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val DIR = "xiaoba_covers"
        private const val MAX_EDGE = 320
        private const val MAX_FILES = 6
        private const val MAX_INPUT_BYTES = 12 * 1024 * 1024
        private const val MAX_OUTPUT_BYTES = 512 * 1024
        private const val TEMP_MAX_AGE_MS = 60 * 60 * 1000L
        private const val PORT = 9090
    }
}

private class LoopbackCoverServer(
    private val directory: File,
    private val scope: CoroutineScope,
) {
    private var socket: ServerSocket? = null
    private var job: Job? = null

    fun start(): Boolean {
        if (socket != null) return true
        return try {
            socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            job = scope.launch(Dispatchers.IO) {
                while (true) {
                    val client = try { socket?.accept() ?: break } catch (_: Exception) { break }
                    launch { serve(client) }
                }
            }
            true
        } catch (_: Exception) {
            socket?.close(); socket = null; false
        }
    }

    fun url(fileName: String, version: String): String = "http://127.0.0.1:$PORT/$fileName?v=$version"

    fun stop() {
        socket?.close(); socket = null
        job?.cancel(); job = null
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            try {
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))
                val request = reader.readLine().orEmpty()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val path = request.substringAfter(' ', "").substringBefore(' ').substringBefore('?')
                val name = URLDecoder.decode(path.removePrefix("/"), StandardCharsets.UTF_8.name())
                val file = File(directory, name)
                val safe = file.canonicalFile.parentFile == directory.canonicalFile && file.isFile
                val body = if (safe) file.readBytes() else ByteArray(0)
                val writer = OutputStreamWriter(connection.getOutputStream(), StandardCharsets.US_ASCII)
                writer.write(if (safe) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 404 Not Found\r\n")
                writer.write("Content-Type: image/jpeg\r\nCache-Control: no-cache, no-store, must-revalidate\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                writer.flush()
                connection.getOutputStream().write(body)
                connection.getOutputStream().flush()
            } catch (_: Exception) { }
        }
    }

    companion object { private const val PORT = 9090 }
}
