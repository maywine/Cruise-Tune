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
import com.cruisetune.player.steering.SteeringAction
import com.cruisetune.player.steering.SteeringController
import com.cruisetune.player.dashboard.*
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
        const val SORT_QUEUE = "cruise.sort_queue"
        const val RESUME_ON_OPEN = "cruise.resume_open"
        const val RETRY = "cruise.retry"
        const val REMOVE_SOURCE = "cruise.remove_source"
        const val DISCONNECT_TOKEN = "cruise.disconnect_token"
        const val DASHBOARD_RECHECK = "cruise.dashboard_recheck"
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
    private var queueSort: TrackSort? = null
    private var applying = false
    private var persistedIntent = false
    private var screenOffObserved = false
    private lateinit var screenOff: ScreenOffMonitor
    private lateinit var steering: SteeringController
    private lateinit var dashboardSettings: DashboardSettings
    private lateinit var dashboard: DashboardSyncController
    private lateinit var dashboardCovers: com.cruisetune.player.dashboard.DashboardCoverStore
    private val ready = CompletableDeferred<Unit>()
    private var prefetchMessage: String? = null
    private var capacityMessage: String? = null
    private var prefetchGate = "未调度"
    private val recoveryPolicy = PlaybackRecoveryPolicy()
    private val stallTracker = PlaybackStallTracker()
    private data class Recovery(val track: Track, val bypass: MediaCache.Bypass, val position: Long,
        var rebuilding: Boolean = false, var networkFailures: Int = 0)
    private var recovery: Recovery? = null
    private var recoveryAction: Job? = null
    private var recoveryRepair: Job? = null
    private var recoveryGeneration = 0L
    private var automaticPlaybackChange = false
    private var prepareAfterManualSeek = false
    private var recoveryNotice: String? = null
    private var clearRecoveryNoticeOnProgress = false
    private var coverJob: Job? = null
    private var coverTrackId: String? = null
    private var coverSource: ByteArray? = null
    private var coverUri: String? = null
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
        steering = SteeringController(this, scope, app.preferences, app.steeringStatus, execute = ::handleSteeringAction)
        dashboardSettings = DashboardSettings(app.preferences)
        dashboardCovers = com.cruisetune.player.dashboard.DashboardCoverStore(this, scope)
        dashboard = DashboardSyncController(scope, dashboardSettings, app.preferences,
            EcarxBroadcastTransport(this), app.dashboardStatus, onDisabled = ::stopDashboardCovers)
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
                app.playbackMetadata.value=PlaybackMetadata.from(player)
                val restartSelected = prepareAfterManualSeek && events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) prepareAfterManualSeek = false
                if (applying || !ready.isCompleted) {
                    if (applying) dashboard.invalidate("正在更新播放队列")
                    return
                }
                // A terminal error leaves ExoPlayer IDLE. Seeking changes metadata but does not
                // restart its loader, even if the next item is fully cached. Prepare after the
                // seek/transition callbacks have cancelled the old recovery; retain pause intent.
                if (restartSelected && player.playbackState == Player.STATE_IDLE && !screenOff.isScreenOff()) {
                    player.prepare()
                    updateExtras()
                }
                if (player.playbackState == Player.STATE_ENDED) persistedIntent = false
                if (player.playerError == null && (events.contains(Player.EVENT_PLAYER_ERROR) || (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY))) updateExtras()
                if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    persist()
                }
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) cancelPrefetch()
                maybePrefetch()
                syncDashboard()
            }
            override fun onPlayerError(error: PlaybackException) {
                dashboard.invalidate("播放正在恢复，暂不更新仪表")
                handlePlaybackFailure(error)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (!automaticPlaybackChange && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    cancelRecovery(); recoveryPolicy.reset()
                    prepareAfterManualSeek = player.playbackState == Player.STATE_IDLE &&
                        (player.playerError != null || player.playWhenReady)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (recovery?.track?.id != mediaItem?.mediaId) cancelRecovery()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) recoveryPolicy.reset()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!automaticPlaybackChange && !playWhenReady) {
                    val wasRecovering = recovery != null
                    cancelRecovery()
                    if (wasRecovering) player.stop()
                }
                if (playWhenReady && screenOff.isScreenOff()) { pauseForScreenOff(); return }
                if (!applying && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    persistedIntent = playWhenReady
                    if (playWhenReady) {
                        screenOffObserved = false
                        if (!automaticPlaybackChange) { recoveryPolicy.reset(); recoveryNotice = null; updateExtras() }
                    }
                }
            }
        })
        screenOff.start()
        scope.launch {
            val saved = withContext(Dispatchers.IO) { app.database.restore() }
            entries = saved.entries; revision = saved.revision; shuffled = saved.shuffled; persistedIntent = saved.playIntent
            queueSort = app.preferences.getString(TrackSort.QUEUE_PREFERENCE, null)?.let(TrackSort::fromPreference)
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
            syncDashboard()
            while (isActive) {
                delay(2000)
                screenOff.checkNow()
                checkPlaybackStall()
                checkRecoveryProgress()
                if (player.isPlaying) persist()
                if (!app.media.hasRoom) {
                    app.media.downloadManager.currentDownloads.filter { it.state == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING }
                        .forEach { app.media.downloadManager.setStopReason(it.request.id, 1) }
                }
                maybePrefetch()
                syncDashboard(tick = true)
            }
        }
    }
    private fun pauseForScreenOff() {
        if (::dashboard.isInitialized) dashboard.invalidate("屏幕已关闭，保持暂停")
        if (::steering.isInitialized) steering.cancelPending()
        screenOffObserved = true
        persistedIntent = false
        val wasRecovering = recovery != null
        cancelRecovery()
        // pause() also clears playWhenReady while buffering / waiting for network recovery.
        player.pause()
        if (wasRecovering) player.stop()
        cancelPrefetch()
        if (ready.isCompleted) persist()
        updateExtras()
    }
    private fun prepareAndPlay(restartCurrent: Boolean = false) {
        if (screenOff.isScreenOff()) { pauseForScreenOff(); return }
        screenOffObserved = false
        if (restartCurrent) PlaybackOperations.retry(player)
        else { player.prepare(); player.play() }
    }
    private fun syncDashboard(tick: Boolean = false) {
        if (!::dashboard.isInitialized || !ready.isCompleted) return
        if (!dashboardSettings.enabled) {
            stopDashboardCovers()
            return
        }
        val unavailable = when {
            screenOffObserved || screenOff.isScreenOff() -> "屏幕已关闭，保持暂停"
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "其他音频正在使用"
            getSystemService(android.media.AudioManager::class.java).mode != android.media.AudioManager.MODE_NORMAL -> "通话或车机音频模式中"
            player.playerError != null -> "播放正在恢复，暂不更新仪表"
            player.playbackState == Player.STATE_IDLE -> "等待歌曲准备"
            else -> null
        }
        if (unavailable != null) { dashboard.invalidate(unavailable); return }
        val track = entries.getOrNull(player.currentMediaItemIndex)?.track
        if (track == null || player.currentMediaItem?.mediaId != track.id) {
            dashboard.invalidate("等待当前歌曲")
            return
        }
        val metadata = app.playbackMetadata.value.forTrack(track.id)?.mediaMetadata
        prepareDashboardCover(track.id, metadata?.artworkData)
        val title = metadata?.title?.toString().orEmpty().ifBlank { track.title }
        val artist = metadata?.artist?.toString().orEmpty()
        val album = metadata?.albumTitle?.toString().orEmpty()
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: track.durationMs.takeIf { it > 0 }
        val state = when {
            player.isPlaying -> DashboardPlaybackState.PLAYING
            player.playWhenReady && player.playbackState == Player.STATE_BUFFERING -> DashboardPlaybackState.BUFFERING
            else -> DashboardPlaybackState.PAUSED
        }
        dashboard.onPlayback(DashboardInput(DashboardSnapshot(track.id, packageName, title, artist, album, state,
            duration, player.currentPosition.coerceAtLeast(0), coverUri)), tick)
    }
    private fun prepareDashboardCover(trackId: String, artwork: ByteArray?) {
        if (artwork == null) {
            if (coverTrackId != trackId) {
                coverJob?.cancel(); coverJob = null
                coverTrackId = trackId; coverSource = null; coverUri = null
            }
            return
        }
        if (coverTrackId == trackId && coverSource === artwork) return
        coverJob?.cancel()
        coverTrackId = trackId
        coverSource = artwork
        coverUri = null
        val sourceKey = "$trackId:${artwork.size}:${artwork.contentHashCode()}"
        coverJob = scope.launch {
            val prepared = dashboardCovers.prepare(trackId, artwork, sourceKey)
            if (coverTrackId == trackId && coverSource === artwork && prepared != null) {
                coverUri = prepared.uri
                syncDashboard()
            }
        }
    }
    private fun stopDashboardCovers() {
        coverJob?.cancel()
        coverJob = null
        coverTrackId = null
        coverSource = null
        coverUri = null
        if (::dashboardCovers.isInitialized) dashboardCovers.stop()
    }
    private fun handleSteeringAction(action: SteeringAction, valid: () -> Boolean) {
        // Never replay key presses collected during startup or a long-running queue update.
        if (!ready.isCompleted) { steering.result("曲库尚未就绪，请稍后按键"); return }
        val received = android.os.SystemClock.uptimeMillis()
        scope.launch {
            commandLock.withLock {
                if (!valid() || android.os.SystemClock.uptimeMillis() - received > 1000) return@withLock
                if (screenOff.isScreenOff()) { steering.result("屏幕已关闭，保持暂停"); return@withLock }
                val audio = getSystemService(android.media.AudioManager::class.java)
                if (audio.mode != android.media.AudioManager.MODE_NORMAL || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
                    (audio.isMusicActive && !player.playWhenReady)) {
                    steering.result("其他音频正在使用，未执行按键动作"); return@withLock
                }
                if (player.mediaItemCount == 0) { steering.result("请先从曲库播放歌曲"); return@withLock }
                when (action) {
                    SteeringAction.NONE -> return@withLock
                    SteeringAction.TOGGLE -> if (player.playWhenReady) player.pause() else prepareAndPlay(player.playerError != null || player.playbackState == Player.STATE_ENDED)
                    SteeringAction.PLAY -> if (!player.playWhenReady || player.playerError != null) prepareAndPlay(player.playerError != null || player.playbackState == Player.STATE_ENDED)
                    SteeringAction.PAUSE -> player.pause()
                    SteeringAction.NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem() else {
                        steering.result("已到队列末尾"); return@withLock
                    }
                    SteeringAction.PREVIOUS -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
                }
                steering.result("已执行：${action.label}")
            }
        }
    }
    private fun snapshot() = PlaybackSnapshot(revision, entries, player.currentMediaItemIndex.coerceAtLeast(0), if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0), persistedIntent, player.repeatMode, shuffled)
    private fun persist() { if (!applying && entries.isNotEmpty()) save(snapshot()) }
    private fun updateExtras(error: String? = null) {
        session.setSessionExtras(Bundle().apply { putBoolean("shuffled", shuffled); if(entries.isNotEmpty())queueSort?.let { putString("queueSort", it.name) }; putBoolean("needsLogin", userError(player.playerError)?.needsLogin == true); if (packageName.endsWith(".authcheck")) putString("prefetchGate", prefetchGate); (capacityMessage ?: prefetchMessage)?.let { putString("prefetchStatus", it) };
            recoveryNotice?.let { putString("recoveryStatus", it) }
            (error ?: player.playerError?.let { readableError(it.cause ?: it) })?.let { putString("error", it) } })
    }
    private fun cancelRecovery() {
        recoveryGeneration++
        stallTracker.reset()
        recoveryAction?.cancel(); recoveryAction = null
        recoveryRepair?.cancel(); recoveryRepair = null
        recovery?.let { app.media.endBypass(it.bypass) }
        recovery = null; recoveryNotice = null; clearRecoveryNoticeOnProgress = false
    }
    private fun retryRecoveryNetworkFailure(error: PlaybackException, attempt: Recovery) {
        val generation = recoveryGeneration
        attempt.networkFailures = (attempt.networkFailures + 1).coerceAtMost(5)
        recoveryAction?.cancel()
        recoveryNotice = "正在缓冲"; updateExtras()
        // Normally the load policy retains transient failures. Also handle a transient error
        // surfaced by the player without clearing the bypass or consuming a file-failure budget.
        recoveryAction = scope.launch {
            try {
                delay(NetworkRetry.delayMs(attempt.networkFailures))
                commandLock.withLock {
                    if (generation != recoveryGeneration || player.currentMediaItem?.mediaId != attempt.track.id ||
                        player.playerError !== error || !player.playWhenReady || screenOff.isScreenOff()) return@withLock
                    automaticPlaybackChange = true
                    try { prepareAndPlay(restartCurrent = true) } finally { automaticPlaybackChange = false }
                    updateExtras()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == recoveryGeneration) {
                    cancelRecovery(); player.pause(); persistedIntent = false
                    updateExtras("自动恢复未完成，请手动重试")
                }
            } finally {
                if (recoveryAction === coroutineContext[Job]) recoveryAction = null
            }
        }
    }
    private fun handlePlaybackFailure(error: PlaybackException) {
        updateExtras(readableError(error.cause ?: error))
        val track = entries.getOrNull(player.currentMediaItemIndex)?.track ?: return
        if (!ready.isCompleted || !player.playWhenReady || screenOff.isScreenOff() ||
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return
        val attempt = recovery
        if (attempt != null && NetworkRetry.isTransient(error)) {
            retryRecoveryNetworkFailure(error, attempt); return
        }
        val stalled = PlaybackRecoveryPolicy.isProgressFailure(error)
        if (!PlaybackRecoveryPolicy.isFileFailure(error) && !(stalled && attempt == null && canRecoverStalledCache(track))) {
            cancelRecovery(); updateExtras(readableError(error.cause ?: error)); return
        }
        scheduleRecovery(track, error, stalled)
    }
    private fun canRecoverStalledCache(track: Track) = app.media.canRepairStreaming(track) && app.media.hasCompleteStreamingCache(track)
    private fun checkPlaybackStall() {
        val track = entries.getOrNull(player.currentMediaItemIndex)?.track
        val active = ready.isCompleted && !applying && !automaticPlaybackChange && player.playerError == null &&
            player.playWhenReady && !screenOff.isScreenOff() && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            player.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)
        // Only the initial cached playback can trigger a probe. Its uncached network load may
        // legitimately wait; neither that wait nor state flapping proves the file is broken.
        val eligible = active && track != null && recovery == null && canRecoverStalledCache(track)
        if (stallTracker.update(track?.cacheKey, player.currentPosition, eligible, android.os.SystemClock.elapsedRealtime())) {
            scheduleRecovery(checkNotNull(track), null, stalled = true)
        }
    }
    private fun scheduleRecovery(track: Track, error: PlaybackException?, stalled: Boolean) {
        val generation = recoveryGeneration
        val position = player.currentPosition
        recoveryAction?.cancel()
        recoveryAction = scope.launch {
            try {
            // Leave the listener dispatch before issuing new player commands. User actions can cancel this turn.
            yield()
            commandLock.withLock {
                if (generation != recoveryGeneration || player.currentMediaItem?.mediaId != track.id ||
                    player.playerError !== error || !player.playWhenReady || screenOff.isScreenOff() ||
                    player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return@withLock
                if (stalled && player.currentPosition - position >= 250) { stallTracker.reset(); return@withLock }
                val canRepair = app.media.canRepairStreaming(track)
                if (stalled && (recovery != null || !canRecoverStalledCache(track))) { stallTracker.reset(); return@withLock }
                if (canRepair && recovery == null && recoveryPolicy.tryRecovery(track.cacheKey)) {
                    cancelPrefetch()
                    stallTracker.reset()
                    val attempt = Recovery(track, app.media.beginBypass(track), player.currentPosition.coerceAtLeast(0))
                    recovery = attempt
                    recoveryNotice = if (stalled) "播放进度未前进，正在重新读取（1/1）" else "缓存读取异常，正在重新读取（1/1）"
                    automaticPlaybackChange = true
                    try { prepareAndPlay(restartCurrent = true) } finally { automaticPlaybackChange = false }
                    updateExtras()
                } else if (!stalled) {
                    skipFailedTrack(track)
                } else {
                    cancelRecovery(); player.pause(); player.stop(); persistedIntent = false; persist()
                    recoveryNotice = "播放未能恢复，请手动重试或切换歌曲"; updateExtras()
                }
            }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                cancelRecovery(); player.pause(); persistedIntent = false
                updateExtras("自动恢复未完成，请手动重试")
            }
        }
    }
    private fun skipFailedTrack(track: Track) {
        stallTracker.reset()
        recovery?.let { app.media.endBypass(it.bypass) }; recovery = null
        recoveryRepair?.cancel(); recoveryRepair = null
        recoveryPolicy.failed(track.cacheKey)
        val next = recoveryPolicy.nextIndex(entries.map { it.track.cacheKey }, player.currentMediaItemIndex) { index ->
            val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
            player.currentTimeline.getNextWindowIndex(index, repeat, player.shuffleModeEnabled)
        }
        automaticPlaybackChange = true
        try {
            if (next == null) {
                if (player.playbackState != Player.STATE_IDLE) player.stop()
                player.pause(); persistedIntent = false
                recoveryNotice = "没有可继续播放的歌曲，请检查文件后重试"
            } else {
                cancelPrefetch()
                player.seekTo(next, 0); prepareAndPlay()
                recoveryNotice = "已跳过无法播放的歌曲"; clearRecoveryNoticeOnProgress = true
            }
        } finally { automaticPlaybackChange = false }
        persist(); updateExtras()
    }
    private fun checkRecoveryProgress() {
        if (clearRecoveryNoticeOnProgress && player.isPlaying && player.currentPosition >= 1500) {
            recoveryNotice = null; clearRecoveryNoticeOnProgress = false; updateExtras()
        }
        val attempt = recovery ?: return
        if (player.currentMediaItem?.mediaId != attempt.track.id || !player.playWhenReady || screenOff.isScreenOff()) {
            cancelRecovery(); updateExtras(); return
        }
        // Elapsed time and connectivity capabilities are not evidence of a broken file.
        // Keep the current load (and its network retries) until progress, a file error or user action.
        if (attempt.rebuilding || !player.isPlaying || player.currentPosition < attempt.position + 1500) return
        attempt.rebuilding = true
        recoveryNotice = "已恢复播放，正在重建当前歌曲缓存"; updateExtras()
        recoveryRepair = scope.launch {
            try {
                prefetch.cancelAndJoin()
                maybePrefetch()
                retryCacheRepair(
                    repair = { app.media.repairStreaming(attempt.track, attempt.bypass) },
                    onRetry = { error ->
                        if (recovery === attempt) {
                            recoveryNotice = if (error is CacheStorageUnavailable) "播放中，等待缓存空间" else "播放中，缓存稍后重试"
                            updateExtras(); maybePrefetch()
                        }
                    },
                )
                if (recovery !== attempt) return@launch
                app.media.endBypass(attempt.bypass); recovery = null
                recoveryNotice = null; updateExtras(); maybePrefetch()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (recovery === attempt) {
                    // The uncached playback can continue even if the background replacement fails.
                    recoveryNotice = "已恢复播放，缓存修复未完成"; updateExtras()
                    maybePrefetch()
                }
            }
        }
    }
    private suspend fun playTrack(id: String, sourceId: String?) {
        ready.await()
        cancelRecovery(); recoveryPolicy.reset()
        val order = TrackSort.fromPreference(app.preferences.getString(TrackSort.PREFERENCE, null))
        val tracks = withContext(Dispatchers.IO) { order.sorted(app.database.tracks(sourceId)).let { list ->
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
        rememberQueueSort(order)
        applying = false; updateExtras(); cancelPrefetch()
    }
    private suspend fun removeSources(ids: Set<String>) {
        if (ids.isEmpty()) return
        cancelRecovery(); recoveryPolicy.reset()
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
        val (ordered, _) = QueuePolicy.reorder(entries, player.currentMediaItem?.mediaId, !shuffled)
        applying = true
        try {
            PlaybackOperations.reorder(player, ordered)
            entries = ordered; shuffled = !shuffled; revision = maxOf(revision + 1, System.currentTimeMillis())
            save(snapshot(), true)
        } finally { applying = false }
        updateExtras(); cancelPrefetch(); maybePrefetch()
    }
    private fun sortQueue(order: TrackSort) {
        if (entries.isEmpty()) return
        val sorted = order.queue(snapshot(), maxOf(revision + 1, System.currentTimeMillis()))
        applying = true
        try {
            PlaybackOperations.reorder(player, sorted.entries)
            entries = sorted.entries; revision = sorted.revision; shuffled = false
            save(snapshot(), true)
            rememberQueueSort(order)
        } finally { applying = false }
        updateExtras(); cancelPrefetch(); maybePrefetch()
    }
    private fun rememberQueueSort(order: TrackSort) {
        queueSort = order
        app.preferences.edit().putString(TrackSort.QUEUE_PREFERENCE, order.name).apply()
    }
    private fun cancelPrefetch() { prefetch.cancel() }
    private fun maybePrefetch() {
        if (!ready.isCompleted || applying || recovery?.rebuilding == false) return
        val wanted = LookAheadPlan.select(entries.map { it.track }, player.currentMediaItemIndex, player.repeatMode, nextIndex = { index ->
            player.currentTimeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
        }).filterNot { it.cacheKey == recovery?.track?.cacheKey }
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
        if (::dashboard.isInitialized) dashboard.close()
        stopDashboardCovers()
        if (::dashboardCovers.isInitialized) dashboardCovers.close()
        steering.close()
        screenOff.stop()
        cancelRecovery()
        persist(); writes.close(); cancelPrefetch(); scope.cancel()
        session.release(); player.release()
        app.playbackMetadata.value=PlaybackMetadata()
        super.onDestroy()
    }
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { try { f.set(block()) } catch (e: Exception) { f.setException(e) } }
        return f
    }
    private inner class Callbacks : MediaLibrarySession.Callback {
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, mediaButtonIntent: Intent): Boolean {
            val key = androidx.core.content.IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
            // Keep standard media handling unless an actual matching OneOS event already owns this press.
            return key != null && ::steering.isInitialized && steering.onMediaButton(key)
        }
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            if (controller.packageName == packageName) listOf(PLAY_TRACK, SHUFFLE, SORT_QUEUE, RESUME_ON_OPEN, RETRY, REMOVE_SOURCE, DISCONNECT_TOKEN, DASHBOARD_RECHECK).forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
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
                    SORT_QUEUE -> sortQueue(TrackSort.fromPreference(args.getString("sort")))
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
                    RETRY -> { cancelRecovery(); recoveryPolicy.reset(); cancelPrefetch(); prepareAndPlay(restartCurrent = true); updateExtras() }
                    DASHBOARD_RECHECK -> dashboard.requestResend()
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
            cancelRecovery(); recoveryPolicy.reset()
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
