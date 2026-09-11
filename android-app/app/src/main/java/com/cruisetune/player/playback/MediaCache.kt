package com.cruisetune.player.playback

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.*
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.R
import com.cruisetune.player.core.Track
import com.cruisetune.player.core.UserError
import com.cruisetune.player.data.JsonCodec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

@UnstableApi
class MediaCache(private val app: CruiseApplication) {
    private val databaseProvider = app.mediaDatabase
    private val stat get() = StatFs(app.filesDir.absolutePath)
    val reserveBytes get() = maxOf(1024L * 1024 * 1024, stat.totalBytes / 10)
    val hasRoom get() = stat.availableBytes > reserveBytes
    val streamLimitBytes: Long = run {
        val requested = app.preferences.getLong("cacheLimit", 2L * 1024 * 1024 * 1024)
        minOf(requested, (stat.availableBytes - reserveBytes).coerceAtLeast(0))
    }
    val stream = SimpleCache(File(app.filesDir, "stream-cache"), LeastRecentlyUsedCacheEvictor(streamLimitBytes), databaseProvider)
    val offline = SimpleCache(File(app.filesDir, "offline-cache"), NoOpCacheEvictor(), databaseProvider)
    private val network = DataSource.Factory { ResolvingTrackSource(app) }
    val streamFactory: CacheDataSource.Factory = CacheDataSource.Factory().setCache(stream)
        .setCacheWriteDataSinkFactory { CompletingCacheSink(CacheDataSink.Factory().setCache(stream)) }
        .setUpstreamDataSourceFactory(network).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val playbackFactory: CacheDataSource.Factory = CacheDataSource.Factory().setCache(offline)
        .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(streamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val downloadManager = DownloadManager(app, app.offlineIndex,
        DefaultDownloaderFactory(CacheDataSource.Factory().setCache(offline).setUpstreamDataSourceFactory(network), Executors.newFixedThreadPool(2))).apply {
        maxParallelDownloads = 1
        minRetryCount = 2
    }
    val notificationHelper = DownloadNotificationHelper(app, "offline-downloads")

    fun mediaItem(track: Track): MediaItem = MediaItem.Builder().setMediaId(track.id)
        .setUri("cruisetune://track/${track.id}").setCustomCacheKey(track.cacheKey).setMimeType(track.mimeType)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setExtras(Bundle().apply { putString("track", JsonCodec.encode(track)) })
            .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

    fun status(track: Track): String {
        if (track.localUri.isNotBlank()) return "本地音乐"
        val item = downloadManager.downloadIndex.getDownload(track.cacheKey)
        if (item?.state == Download.STATE_COMPLETED && isComplete(track, item.contentLength)) return "可离线播放"
        if (isPrefetchComplete(track)) return "已缓存完整"
        if (item?.state == Download.STATE_DOWNLOADING) return "正在保留离线 ${item.percentDownloaded.toInt().coerceAtLeast(0)}%"
        if (item?.state == Download.STATE_QUEUED) return "等待下载"
        if (item?.state == Download.STATE_STOPPED) return "下载已暂停"
        if (stream.getCachedBytes(track.cacheKey, 0, C.LENGTH_UNSET.toLong()) > 0 || offline.getCachedBytes(track.cacheKey, 0, C.LENGTH_UNSET.toLong()) > 0) return "已缓存部分"
        if (item?.state == Download.STATE_FAILED) return "下载未完成"
        return "在线"
    }
    fun isComplete(track: Track, reportedLength: Long = -1): Boolean {
        val length = reportedLength.takeIf { it > 0 } ?: track.size.takeIf { it > 0 }
            ?: ContentMetadata.getContentLength(offline.getContentMetadata(track.cacheKey))
        return length > 0 && offline.isCached(track.cacheKey, 0, length)
    }
    fun contentLength(track: Track): Long = track.size.takeIf { it > 0 }
        ?: ContentMetadata.getContentLength(stream.getContentMetadata(track.cacheKey)).takeIf { it > 0 }
        ?: ContentMetadata.getContentLength(offline.getContentMetadata(track.cacheKey))
    fun isPrefetchComplete(track: Track): Boolean {
        if (track.localUri.isNotBlank()) return true
        val length = contentLength(track)
        return length > 0 && (offline.isCached(track.cacheKey, 0, length) || stream.isCached(track.cacheKey, 0, length))
    }
    fun prefetchAttempt(track: Track): PrefetchAttempt = WholeTrackCacheAttempt(track,
        CacheDataSource.Factory().setCache(stream).setUpstreamDataSourceFactory(network)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE),
        contentLength(track), streamLimitBytes, { hasRoom })
    fun keepOffline(track: Track) {
        if (track.localUri.isNotBlank()) throw UserError("这首音乐已经保存在本地")
        if (!hasRoom || track.size > (stat.availableBytes - reserveBytes)) throw UserError("存储空间不足，请清理缓存后重试")
        val request = DownloadRequest.Builder(track.cacheKey, Uri.parse("cruisetune://track/${track.id}"))
            .setCustomCacheKey(track.cacheKey).setMimeType(track.mimeType).setData(track.title.toByteArray()).build()
        DownloadService.sendAddDownload(app, OfflineDownloadService::class.java, request, true)
        DownloadService.sendResumeDownloads(app, OfflineDownloadService::class.java, true)
    }
    fun removeOffline(track: Track) { DownloadService.sendRemoveDownload(app, OfflineDownloadService::class.java, track.cacheKey, true) }
    fun clearStreaming() { stream.keys.toList().forEach { stream.removeResource(it) } }
}

@UnstableApi
internal class WholeTrackCacheAttempt(
    private val track: Track, private val factory: CacheDataSource.Factory,
    private val length: Long, private val limitBytes: Long, private val hasRoom: () -> Boolean,
) : PrefetchAttempt {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var writer: androidx.media3.datasource.cache.CacheWriter? = null
    override suspend fun cache() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw kotlinx.coroutines.CancellationException()
        if (!hasRoom() || length > limitBytes) throw UserError("缓存空间不足，后续歌曲暂缓下载")
        val spec = DataSpec.Builder().setUri("cruisetune://track/${track.id}").setKey(track.cacheKey)
            .setLength(C.LENGTH_UNSET.toLong()).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
        val attempt = androidx.media3.datasource.cache.CacheWriter(factory.createDataSource(), spec, ByteArray(64 * 1024)) { total, _, _ ->
            if (!hasRoom() || total > limitBytes) throw UserError("缓存空间不足，后续歌曲暂缓下载")
            if (length > 0 && total > 0 && total != length) throw UserError("文件大小已变化，请刷新曲库")
        }
        writer = attempt
        if (cancelled.get()) attempt.cancel()
        try { attempt.cache() } finally { writer = null }
    }
    override fun cancel() { cancelled.set(true); writer?.cancel() }
}

@UnstableApi
internal class ResolvingTrackSource(
    private val lookup: (String) -> Track?,
    private val resolve: (Track) -> com.cruisetune.player.core.ReadRequest,
    private val upstream: DataSource.Factory,
    private val onAuthorizationFailure: (Track, com.cruisetune.player.core.ReadRequest) -> Unit = { _, _ -> },
) : DataSource {
    constructor(app: CruiseApplication) : this(app.library::findTrack, { runBlocking { app.library.readRequest(it) } }, DefaultDataSource.Factory(app, OkHttpDataSource.Factory(app.streamHttp)), { track, request -> runBlocking { app.library.onReadAuthFailure(track, request) } })
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; delegate?.addTransferListener(transferListener) }
    override fun open(dataSpec: DataSpec): Long {
        val track = dataSpec.uri.takeIf { it.scheme == "cruisetune" }?.lastPathSegment?.let(lookup)
            ?: throw UserError("歌曲信息不可用，请重新选择")
        if (dataSpec.key != null && dataSpec.key != track.cacheKey) throw UserError("文件内容已更新，请从曲库重新播放")
        for (attempt in 0..1) {
            val resolved = resolve(track)
            val source = upstream.createDataSource().also { ds -> listeners.forEach(ds::addTransferListener) }
            delegate = source
            try {
                // Keep position, length and stable key across URL renewal. Resolution is below both caches.
                return source.open(dataSpec.buildUpon().setUri(resolved.url).setHttpRequestHeaders(resolved.headers).build())
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                source.close(); delegate = null
                if (attempt == 1 || e.responseCode !in listOf(401, 403)) throw e
                onAuthorizationFailure(track, resolved)
            } catch (e: IOException) { source.close(); delegate = null; throw e }
        }
        throw UserError("读取失败，请重新连接夸克")
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { delegate?.close(); delegate = null }
}

@UnstableApi
class OfflineDownloadService : DownloadService(42, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, "offline-downloads", R.string.offline_channel, 0) {
    private val media get() = (application as CruiseApplication).media
    override fun getDownloadManager(): DownloadManager = media.downloadManager
    override fun getScheduler(): androidx.media3.exoplayer.scheduler.Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        media.notificationHelper.buildProgressNotification(this, R.drawable.ic_music_note, null, "正在保留音乐供离线播放", downloads, notMetRequirements)
}
