package com.cruisetune.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import com.cruisetune.player.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@UnstableApi
class PlaybackService : MediaLibraryService() {
    companion object {
        const val PLAY_TRACK = "cruise.play_track"
        const val SHUFFLE = "cruise.shuffle"
        const val RESUME_ON_OPEN = "cruise.resume_open"
        const val RETRY = "cruise.retry"
        const val REMOVE_SOURCE = "cruise.remove_source"
        const val DISCONNECT_TOKEN = "cruise.disconnect_token"
    }
    private val app get() = application as CruiseApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private data class DiskWrite(val action: suspend () -> Unit, val completed: CompletableDeferred<Unit>? = null)
    private val writes = Channel<DiskWrite>(Channel.UNLIMITED)
    private val commandLock = Mutex()
    private fun save(snapshot: PlaybackSnapshot, queue: Boolean = false) {
        writes.trySend(DiskWrite({ if (queue) app.database.saveQueue(snapshot) else app.database.savePosition(snapshot) }))
    }
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private var entries = emptyList<QueueEntry>()
    private var revision = 0L
    private var shuffled = false
    private var applying = false
    private var persistedIntent = false
    private var screenOffObserved = false
    private lateinit var screenOff: ScreenOffMonitor
    private val ready = CompletableDeferred<Unit>()
    private var prefetchMessage: String? = null
    private var capacityMessage: String? = null
    private var prefetchGate = "未调度"
    private val prefetch by lazy { LookAheadPrefetch(scope, app.media::isPrefetchComplete, app.media::prefetchAttempt,
        android.os.SystemClock::elapsedRealtime, report = { message ->
            if (message != prefetchMessage) { prefetchMessage = message; updateExtras() }
        }) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.media.playbackFactory).setLoadErrorHandlingPolicy(PersistentNetworkLoadPolicy()))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20000, 60000, 1500, 3500).build())
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
            }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, Callbacks()).setSessionActivity(activity).build()
        screenOff = ScreenOffMonitor(this, ::pauseForScreenOff)
        diskScope.launch {
            for (write in writes) {
                try {
                    write.action(); write.completed?.complete(Unit)
                } catch (e: Exception) {
                    write.completed?.completeExceptionally(e)
                    withContext(Dispatchers.Main) { updateExtras("播放位置暂时无法保存，请检查存储空间") }
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (applying || !ready.isCompleted) return
                if (player.playbackState == Player.STATE_ENDED) persistedIntent = false
                if (player.playerError == null && (events.contains(Player.EVENT_PLAYER_ERROR) || (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY))) updateExtras()
                if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    persist()
                }
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) cancelPrefetch()
                maybePrefetch()
            }
            override fun onPlayerError(error: PlaybackException) { updateExtras(readableError(error.cause ?: error)) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && screenOff.isScreenOff()) { pauseForScreenOff(); return }
                if (!applying && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    persistedIntent = playWhenReady
                    if (playWhenReady) screenOffObserved = false
                }
            }
        })
        screenOff.start()
        scope.launch {
            val saved = withContext(Dispatchers.IO) { app.database.restore() }
            entries = saved.entries; revision = saved.revision; shuffled = saved.shuffled; persistedIntent = saved.playIntent
            if (entries.isNotEmpty()) {
                applying = true
                player.setMediaItems(entries.map { app.media.mediaItem(it.track) }, saved.index, saved.positionMs)
                player.repeatMode = saved.repeatMode
                // Loading the screen restores metadata and progress without opening the network or playing.
                player.playWhenReady = false
                applying = false
            }
            ready.complete(Unit)
            // An OFF event may arrive while the saved queue is loading. Do not restore its old play intent.
            if (screenOffObserved || screenOff.isScreenOff()) pauseForScreenOff()
            updateExtras()
            while (isActive) {
                delay(2000)
                screenOff.checkNow()
                if (player.isPlaying) persist()
                if (!app.media.hasRoom) {
                    app.media.downloadManager.currentDownloads.filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING }
                        .forEach { app.media.downloadManager.setStopReason(it.request.id, 1) }
                }
                maybePrefetch()
            }
        }
    }
    private fun pauseForScreenOff() {
        screenOffObserved = true
        persistedIntent = false
        // pause() also clears playWhenReady while buffering / waiting for network recovery.
        player.pause()
        cancelPrefetch()
        if (ready.isCompleted) persist()
        updateExtras()
    }
    private fun prepareAndPlay() {
        if (screenOff.isScreenOff()) { pauseForScreenOff(); return }
        screenOffObserved = false
        player.prepare(); player.play()
    }
    private fun snapshot() = PlaybackSnapshot(revision, entries, player.currentMediaItemIndex.coerceAtLeast(0), if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0), persistedIntent, player.repeatMode, shuffled)
    private fun persist() { if (!applying && entries.isNotEmpty()) save(snapshot()) }
    private fun updateExtras(error: String? = null) {
        session.setSessionExtras(Bundle().apply { putBoolean("shuffled", shuffled); putBoolean("needsLogin", userError(player.playerError)?.needsLogin == true); if (packageName.endsWith(".authcheck")) putString("prefetchGate", prefetchGate); (capacityMessage ?: prefetchMessage)?.let { putString("prefetchStatus", it) };
            (error ?: player.playerError?.let { readableError(it.cause ?: it) })?.let { putString("error", it) } })
    }
    private suspend fun playTrack(id: String, sourceId: String?) {
        ready.await()
        val tracks = withContext(Dispatchers.IO) { app.database.tracks(sourceId).let { list ->
            if (list.any { it.id == id }) list else listOfNotNull(app.database.findTrack(id))
        } }
        if (screenOff.isScreenOff()) { pauseForScreenOff(); return }
        val index = tracks.indexOfFirst { it.id == id }
        if (index < 0) throw UserError("歌曲已不在目录中，请刷新后重试")
        applying = true
        entries = tracks.mapIndexed { i, t -> QueueEntry(t, i) }
        revision = maxOf(revision + 1, System.currentTimeMillis())
        shuffled = false; persistedIntent = true
        save(PlaybackSnapshot(revision, entries, index, 0, true, player.repeatMode, false), true)
        player.setMediaItems(tracks.map(app.media::mediaItem), index, 0)
        prepareAndPlay()
        applying = false; updateExtras(); cancelPrefetch()
    }
    private suspend fun removeSources(ids: Set<String>) {
        if (ids.isEmpty()) return
        val before = snapshot()
        val remaining = SourceRemoval.queue(before, ids, maxOf(revision + 1, System.currentTimeMillis()))
        val removedIds = withContext(Dispatchers.IO) { app.database.trackIdsForSources(ids) } +
            entries.filter { it.track.sourceId in ids }.map { it.track.id }
        val offlineRequests = withContext(Dispatchers.IO) { app.offlineIndex.getDownloads().use { cursor ->
            buildSet { while(cursor.moveToNext()) if(cursor.download.request.uri.lastPathSegment in removedIds) add(cursor.download.request.id) }
        } }
        applying = true
        player.pause(); cancelPrefetch()
        try {
            val completion = CompletableDeferred<Unit>()
            // Serialize removal after all earlier saves, preventing stale queued writes from reviving it.
            writes.send(DiskWrite({ app.library.removeSources(ids, remaining) }, completion))
            completion.await()
            entries = remaining.entries; revision = remaining.revision; persistedIntent = remaining.playIntent
            if (entries.isEmpty()) player.clearMediaItems()
            else player.setMediaItems(entries.map { app.media.mediaItem(it.track) }, remaining.index, remaining.positionMs)
            (offlineRequests + app.media.downloadManager.currentDownloads.filter { it.request.uri.lastPathSegment in removedIds }.map { it.request.id })
                .forEach { app.media.downloadManager.removeDownload(it) }
            if (remaining.playIntent) prepareAndPlay()
            updateExtras("已移除本机音乐来源")
        } finally { applying = false; persist() }
    }
    private suspend fun toggleShuffle() {
        ready.await()
        if (entries.isEmpty()) return
        val position = player.currentPosition
        val playing = player.playWhenReady
        val (ordered, index) = QueuePolicy.reorder(entries, player.currentMediaItem?.mediaId, !shuffled)
        applying = true
        entries = ordered; shuffled = !shuffled; revision = maxOf(revision + 1, System.currentTimeMillis())
        save(PlaybackSnapshot(revision, entries, index, position, persistedIntent, player.repeatMode, shuffled), true)
        player.setMediaItems(entries.map { app.media.mediaItem(it.track) }, index, position)
        player.prepare(); player.playWhenReady = playing
        applying = false; updateExtras(); cancelPrefetch()
    }
    private fun cancelPrefetch() { prefetch.cancel() }
    private fun maybePrefetch() {
        if (!ready.isCompleted || applying) return
        val wanted = LookAheadPlan.select(entries.map { it.track }, player.currentMediaItemIndex, player.repeatMode, nextIndex = { index ->
            player.currentTimeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
        })
        // Reserve room for the currently playing file as well as the upcoming files; avoid LRU churn.
        val current = entries.getOrNull(player.currentMediaItemIndex)?.track
        var budget = app.media.streamLimitBytes
        if (current != null && wanted.none { it.cacheKey == current.cacheKey } && current.localUri.isBlank()) {
            budget -= app.media.contentLength(current).takeIf { it > 0 } ?: minOf(budget, 128L * 1024 * 1024)
        }
        val fitting = wanted.filter { track ->
            if (track.localUri.isNotBlank() || app.media.isComplete(track)) true
            else {
                val length = app.media.contentLength(track).takeIf { it > 0 } ?: budget.coerceAtLeast(0)
                (budget > 0 && length <= budget).also { fits -> if (fits) budget -= length }
            }
        }
        val enabled = app.preferences.getBoolean("prefetchNextTracks", true)
        val newCapacityMessage = if (enabled && wanted.isNotEmpty() && (fitting.size < wanted.size || !app.media.hasRoom)) "缓存空间不足，暂时无法存下后 3 首" else null
        if (newCapacityMessage != capacityMessage) { capacityMessage = newCapacityMessage; updateExtras() }
        val canDownload = enabled && !screenOff.isScreenOff() && player.playWhenReady && player.playerError == null &&
            player.totalBufferedDuration >= 15000 && app.media.hasRoom
        if (packageName.endsWith(".authcheck")) {
            prefetchGate = "目标 ${fitting.size}/${wanted.size} · 开关 $enabled · 播放 ${player.playWhenReady} · 缓冲 ${player.totalBufferedDuration} ms · 空间 ${app.media.hasRoom} · 下载 $canDownload"
            updateExtras()
        }
        prefetch.update(fitting, canDownload)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session
    override fun onDestroy() {
        screenOff.stop()
        persist(); writes.close(); cancelPrefetch(); scope.cancel()
        session.release(); player.release()
        super.onDestroy()
    }
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { try { f.set(block()) } catch (e: Exception) { f.setException(e) } }
        return f
    }
    private inner class Callbacks : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            if (controller.packageName == packageName) listOf(PLAY_TRACK, SHUFFLE, RESUME_ON_OPEN, RETRY, REMOVE_SOURCE, DISCONNECT_TOKEN).forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(Player.Commands.Builder().addAllCommands().remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> = future {
            ready.await()
            commandLock.withLock {
            try {
                when (customCommand.customAction) {
                    PLAY_TRACK -> playTrack(args.getString("trackId") ?: "", args.getString("sourceId"))
                    SHUFFLE -> toggleShuffle()
                    RESUME_ON_OPEN -> if (app.preferences.getBoolean("resumeOnOpen", false) && persistedIntent && entries.isNotEmpty()) { prepareAndPlay() }
                    REMOVE_SOURCE -> removeSources(setOf(args.getString("sourceId") ?: throw UserError("请选择要移除的目录")))
                    DISCONNECT_TOKEN -> {
                        val account = args.getString("accountId") ?: throw UserError("请选择要移除的账号")
                        val ids = withContext(Dispatchers.IO) { app.database.sources().filter { it.kind == SourceKind.QUARK_OPEN && it.accountId == account }.map { it.id }.toSet() }
                        removeSources(ids)
                        app.openConnections.sessions.disconnect(account)
                        withContext(Dispatchers.IO) { app.database.forgetAccess(SourceKind.QUARK_OPEN, account) }
                        if (app.preferences.getString("quarkDirectAccount",null) == account) withContext(Dispatchers.IO) {
                            check(app.preferences.edit().remove("quarkDirectAccount").commit())
                        }
                        updateExtras("已清除本机 Token 授权")
                    }
                    RETRY -> { cancelPrefetch(); updateExtras(); prepareAndPlay() }
                    else -> return@future SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                }
                SessionResult(SessionResult.RESULT_SUCCESS)
            } catch (e: Exception) { updateExtras(readableError(e)); SessionResult(SessionError.ERROR_IO) }
            }
        }
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            ready.await()
            val s = snapshot()
            MediaSession.MediaItemsWithStartPosition(s.entries.map { app.media.mediaItem(it.track) }, s.index, s.positionMs)
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId("root").setMediaMetadata(MediaMetadata.Builder().setTitle("Cruise Tune").setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            if (page < 0 || pageSize !in 1..1000) return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            val all = withContext(Dispatchers.IO) {
                if (parentId == "root") app.database.sources().map { s ->
                    MediaItem.Builder().setMediaId("source:${s.id}").setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
                } else if (parentId.startsWith("source:")) app.database.tracks(parentId.removePrefix("source:")).map(app.media::mediaItem)
                else emptyList()
            }
            LibraryResult.ofItemList(all.drop((page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(pageSize), params)
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            val track = withContext(Dispatchers.IO) { app.database.findTrack(mediaId) }
            if (track == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(app.media.mediaItem(track), null)
        }
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            ready.await()
            commandLock.withLock {
            val requestedId = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
            val tracks = withContext(Dispatchers.IO) {
                val selected = mediaItems.mapNotNull { app.database.findTrack(it.mediaId) }
                if (selected.size == 1) app.database.tracks(selected.first().sourceId).ifEmpty { selected } else selected
            }
            val index = tracks.indexOfFirst { it.id == requestedId }.coerceAtLeast(0)
            val position = startPositionMs.coerceAtLeast(0)
            if (tracks.isNotEmpty()) {
                entries = tracks.mapIndexed { i, t -> QueueEntry(t, i) }; revision = maxOf(revision + 1, System.currentTimeMillis()); shuffled = false
                save(PlaybackSnapshot(revision, entries, index, position, persistedIntent, player.repeatMode), true)
                updateExtras(); cancelPrefetch()
            }
            MediaSession.MediaItemsWithStartPosition(tracks.map(app.media::mediaItem), index, position)
            }
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> = future {
            val tracks = withContext(Dispatchers.IO) { mediaItems.mapNotNull { app.database.findTrack(it.mediaId) } }
            tracks.map(app.media::mediaItem).toMutableList()
        }
    }
}
