package com.cruisetune.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.cruisetune.player.core.Track
import com.cruisetune.player.core.UserError
import java.io.File
import java.io.InterruptedIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Download a fresh copy before replacing only this song's streaming spans. Offline storage is never passed here. */
@UnstableApi
internal class StreamCacheRepair(
    private val cache: Cache,
    private val network: DataSource.Factory,
    private val temporaryDirectory: File,
    private val limitBytes: Long,
    private val hasRoom: () -> Boolean,
) {
    suspend fun repair(track: Track, versionCurrent: () -> Boolean = { true }, stillCurrent: () -> Boolean) = runInterruptible(Dispatchers.IO) {
        fun checkActive() {
            if (Thread.currentThread().isInterrupted || !stillCurrent()) throw InterruptedIOException("Cache repair cancelled")
            if (!hasRoom()) throw UserError("缓存空间不足，已保留原缓存")
        }
        checkActive()
        if (limitBytes <= 0 || track.size > limitBytes) throw UserError("缓存空间不足，已保留原缓存")
        val temporary = File.createTempFile("stream-repair-", ".audio", temporaryDirectory)
        try {
            val spec = DataSpec.Builder().setUri("cruisetune://track/${track.id}").setKey(track.cacheKey).build()
            val source = network.createDataSource()
            var received = 0L
            try {
                val length = source.open(spec)
                if (length > limitBytes || (track.size > 0 && length > 0 && length != track.size))
                    throw UserError("文件大小已变化，请刷新曲库")
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = source.read(buffer, 0, buffer.size)
                        if (count == C.RESULT_END_OF_INPUT) break
                        received += count
                        if (received > limitBytes || (track.size > 0 && received > track.size))
                            throw UserError("文件大小已变化，请刷新曲库")
                        output.write(buffer, 0, count)
                    }
                }
                if (received == 0L || (length >= 0 && received != length) || (track.size > 0 && received != track.size))
                    throw UserError("重新下载未完整，已保留原缓存")
            } finally { source.close() }
            checkActive()
            if (!versionCurrent()) throw UserError("文件内容已变化，已保留原缓存")
            // The caller has observed real playback progress and joined the old prefetch writer.
            // Incomplete or cancelled downloads above never invalidate the old spans.
            cache.removeResource(track.cacheKey)
            cache.applyContentMetadataMutations(track.cacheKey, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, C.LENGTH_UNSET.toLong())
                ContentMetadataMutations.setRedirectedUri(this, null)
            })
            val writer = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(FileDataSource.Factory()).setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE).createDataSource()
            CacheWriter(writer, DataSpec.Builder().setUri(Uri.fromFile(temporary)).setKey(track.cacheKey).build(),
                ByteArray(64 * 1024)) { _, _, _ -> checkActive() }.cache()
            checkActive()
            if (!cache.isCached(track.cacheKey, 0, received)) throw UserError("缓存修复尚未完成")
        } finally { temporary.delete() }
    }
}
