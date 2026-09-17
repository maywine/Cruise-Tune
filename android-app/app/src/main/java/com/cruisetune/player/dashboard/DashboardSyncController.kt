package com.cruisetune.player.dashboard

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.min

/**
 * Serializes an optional, best-effort vehicle broadcast. Playback, caching and recovery never
 * depend on this controller succeeding.
 */
internal class DashboardSyncController(
    private val scope: CoroutineScope,
    private val settings: DashboardSettings,
    private val preferences: SharedPreferences,
    private val transport: DashboardTransport,
    private val status: MutableStateFlow<DashboardStatus>,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Pending(val epoch: Long, val snapshot: DashboardSnapshot)

    private val policy = DashboardSyncPolicy()
    private var endpoint: DashboardEndpoint? = null
    private var checking: Job? = null
    private var sending = false
    private var pending = java.util.ArrayDeque<Pending>()
    private var coalescing: Job? = null
    private var retry: Job? = null
    private var retryCount = 0
    private var epoch = 0L
    private var latest: DashboardInput? = null
    private var lastSignature: String? = null
    private var lastProgressSentAt = Long.MIN_VALUE
    private var sentCount = 0L
    private var lastSentElapsed: Long? = null
    private var closed = false
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DashboardSettings.ENABLED) scope.launch { onSettingChanged() }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(changed)
        if (settings.enabled) refreshEndpoint()
        else update(DashboardStatusKind.DISABLED, "未开启仪表媒体显示")
    }

    fun onPlayback(input: DashboardInput, tick: Boolean = false) {
        if (closed) return
        latest = input
        if (!settings.enabled) {
            policy.invalidate()
            return
        }
        if (input.invalidated) {
            invalidate(input.invalidationReason.ifBlank { "当前播放不可同步" })
            return
        }
        if (endpoint?.ready != true) {
            refreshEndpoint()
            return
        }
        val now = elapsedMs()
        if (tick && input.snapshot.state == DashboardPlaybackState.PLAYING && now - lastProgressSentAt < 1800L) return
        val outgoing = policy.onPlayback(input)
        if (outgoing.isEmpty()) {
            if (input.snapshot.state == DashboardPlaybackState.PLAYING) update(DashboardStatusKind.READY, "播放中，等待仪表更新")
            else update(DashboardStatusKind.WAITING, "等待实际播放")
            return
        }
        submit(outgoing, coalesce = !tick && outgoing.size == 1 && outgoing.single().state == DashboardPlaybackState.PLAYING)
    }

    /** User initiated: re-check the fixed endpoint and send only an actually playing snapshot. */
    fun requestResend() {
        if (closed || !settings.enabled) return
        endpoint = null
        refreshEndpoint(force = true)
    }

    fun invalidate(reason: String) {
        epoch++
        pending.clear()
        coalescing?.cancel(); coalescing = null
        retry?.cancel(); retry = null
        retryCount = 0
        policy.invalidate()
        lastSignature = null
        if (settings.enabled && !closed) update(DashboardStatusKind.WAITING, reason)
    }

    fun close() {
        if (closed) return
        closed = true
        preferences.unregisterOnSharedPreferenceChangeListener(changed)
        epoch++
        pending.clear()
        checking?.cancel(); checking = null
        coalescing?.cancel(); coalescing = null
        retry?.cancel(); retry = null
        policy.invalidate()
    }

    private fun onSettingChanged() {
        if (closed) return
        if (!settings.enabled) {
            endpoint = null
            invalidate("仪表媒体显示已关闭")
            update(DashboardStatusKind.DISABLED, "未开启仪表媒体显示")
        } else {
            endpoint = null
            refreshEndpoint(force = true)
        }
    }

    private fun refreshEndpoint(force: Boolean = false) {
        if (closed || !settings.enabled || (checking?.isActive == true && !force)) return
        checking?.cancel()
        update(DashboardStatusKind.CHECKING, "正在检查原车媒体服务")
        checking = scope.launch {
            val result = withContext(ioDispatcher) { transport.inspect() }
            if (closed || !settings.enabled) return@launch
            endpoint = result
            if (!result.ready) {
                update(DashboardStatusKind.UNAVAILABLE, result.detail, result)
                return@launch
            }
            update(DashboardStatusKind.WAITING, result.detail, result)
            val input = latest ?: return@launch
            if (!input.invalidated && input.snapshot.state == DashboardPlaybackState.PLAYING) {
                val outgoing = policy.onPlayback(input)
                submit(outgoing, coalesce = false, force = true)
            }
        }
    }

    private fun submit(snapshots: List<DashboardSnapshot>, coalesce: Boolean, force: Boolean = false) {
        if (snapshots.isEmpty() || closed || !settings.enabled) return
        val event = ++epoch
        val start: () -> Unit = start@{
            if (event != epoch || closed || !settings.enabled) return@start
            pending.clear()
            snapshots.forEach { snapshot ->
                val signature = EcarxMediaPayload.signature(snapshot)
                if (force || signature != lastSignature || snapshot.state == DashboardPlaybackState.PLAYING) {
                    pending.addLast(Pending(event, snapshot))
                }
            }
            pump()
        }
        if (coalesce) {
            coalescing?.cancel()
            coalescing = scope.launch {
                delay(200)
                start()
            }
        } else start()
    }

    private fun pump() {
        if (closed || sending || !settings.enabled) return
        val next = if (pending.isEmpty()) null else pending.removeFirst()
        if (next == null) return
        if (next.epoch != epoch) { pump(); return }
        val endpoint = endpoint
        if (endpoint?.ready != true) { refreshEndpoint(); return }
        sending = true
        val intent = EcarxMediaPayload.intent(next.snapshot)
        scope.launch {
            val result = withContext(ioDispatcher) { transport.dispatch(intent) }
            if (closed) return@launch
            sending = false
            when (result) {
                DashboardDispatch.Dispatched -> {
                    lastSignature = EcarxMediaPayload.signature(next.snapshot)
                    if (next.snapshot.state == DashboardPlaybackState.PLAYING) lastProgressSentAt = elapsedMs()
                    retry?.cancel(); retry = null
                    retryCount = 0
                    sentCount++
                    lastSentElapsed = elapsedMs()
                    update(DashboardStatusKind.READY, "已发送，需在仪表媒体菜单确认")
                }
                is DashboardDispatch.Failed -> {
                    if (result.retryable && next.epoch == epoch && pending.isEmpty() && retryCount < 3 && next.snapshot.state == DashboardPlaybackState.PLAYING) {
                        scheduleRetry(next, result.detail)
                    } else {
                        update(DashboardStatusKind.UNAVAILABLE, result.detail, lastFailure = result.detail)
                    }
                }
            }
            pump()
        }
    }

    private fun scheduleRetry(pending: Pending, reason: String) {
        retry?.cancel()
        retryCount++
        val seconds = listOf(2L, 5L, 15L)[min(retryCount - 1, 2)]
        update(DashboardStatusKind.RETRYING, "仪表媒体信息暂时无法发送，将重试", lastFailure = reason)
        retry = scope.launch {
            delay(seconds * 1000)
            if (closed || !settings.enabled || pending.epoch != epoch) return@launch
            this@DashboardSyncController.pending.clear()
            this@DashboardSyncController.pending.addLast(pending)
            pump()
        }
    }

    private fun update(kind: DashboardStatusKind, detail: String, endpoint: DashboardEndpoint? = this.endpoint, lastFailure: String? = null) {
        status.value = DashboardStatus(
            kind = kind,
            detail = detail,
            sentCount = sentCount,
            lastSentElapsedMs = lastSentElapsed,
            receiverVersion = endpoint?.version,
            receiverUid = endpoint?.uid,
            receiverSystemApp = endpoint?.systemApp,
            lastFailure = lastFailure,
        )
    }
}
