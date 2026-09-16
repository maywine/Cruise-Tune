package com.cruisetune.player.steering

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class SteeringController(
    context: Context, private val scope: CoroutineScope, private val preferences: SharedPreferences,
    private val status: MutableStateFlow<SteeringStatus>,
    private val connectionFactory: ((ConnectionReport, (String) -> Unit, (SteeringSignal) -> Unit) -> SteeringConnection)? = null,
    private val execute: (SteeringAction, () -> Boolean) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val settings = SteeringSettings(preferences)
    private var closed = false
    private var listening = false
    private var generation = 0
    private var menuWatch: Job? = null
    private val routes = SteeringInputArbiter()
    private val startedAt = SystemClock.uptimeMillis()
    private val gestures: SteeringGestures = SteeringGestures(SystemClock::uptimeMillis, { delay, action ->
        val runnable = Runnable { action() }; handler.postDelayed(runnable, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(runnable) }; cancel
    }, ::gesture)
    private val menu: SteeringMenuGuard = SteeringMenuGuard({ connection.controlIndex() }) { index ->
        gestures.reset(); routes.clearOneOs()
        status.value = status.value.copy(menu = when (index) {
            2 -> "当前为媒体菜单（2）"
            3 -> "当前为电话菜单（3），不执行播放动作"
            null -> "无法确认按键菜单，不执行播放动作"
            else -> "当前为其他菜单（$index），不执行播放动作"
        })
    }
    private val connection: SteeringConnection = connectionFactory?.invoke(::connectionChanged, ::diagnose, ::receive)
        ?: SteeringBridgeClient(context, scope, ::connectionChanged, ::diagnose, ::receive)
    private fun diagnose(detail: String) {
        if (closed) return
        val line = "${SystemClock.uptimeMillis() - startedAt} ms · ${detail.take(300)}"
        status.value = status.value.copy(trace = (status.value.trace + line).takeLast(32))
    }
    private fun active(run: Int) = !closed && listening && settings.enabled && generation == run
    private fun gesture(key: SteeringKey, gesture: SteeringGesture) {
        val action = settings.action(key, gesture)
        val run = generation; val ticket = menu.revision; val time = SystemClock.uptimeMillis()
        scope.launch {
            // SINGLE may execute 350ms after key receipt. Never rely on the earlier menu sample.
            val allowed = menu.permits(ticket)
            if (!active(run) || SystemClock.uptimeMillis() - time > 1000) return@launch
            if (!allowed) { result("菜单已切换或无法确认，未执行按键动作"); return@launch }
            if (settings.detectOnly) result("检测：${key.label} · ${gesture.label} → ${action.label}（未执行）")
            else if (action != SteeringAction.NONE) {
                execute(action) { active(run) && !settings.detectOnly && menu.index == 2 && menu.revision == ticket }
            } else result("${key.label} · ${gesture.label}：未设置动作")
        }
    }
    private fun connectionChanged(connected: Boolean, text: String, detail: String) {
        if (closed) return
        listening = connected; cancelPending(); menu.reset()
        menuWatch?.cancel(); menuWatch = null
        status.value = status.value.copy(connection = text, detail = detail)
        if (connected) {
            menuWatch = scope.launch {
                while (!closed && listening && settings.enabled) {
                    menu.refresh()
                    delay(500)
                }
            }
        }
    }
    private fun receive(signal: SteeringSignal) {
        if (closed || !settings.enabled) return
        val (code, event, parameter, extra, uid, time) = signal
        val key = SteeringKey.fromCode(code)
        val count = status.value.receivedEvents + 1
        status.value = status.value.copy(receivedEvents = count,
            lastEvent = "已收到 $count 次 · ${key?.label ?: "未知键"}（$code）· ${event.label}")
        diagnose("OneOS $code ${event.name} 参数=$parameter/$extra UID=$uid")
        if (settings.detectOnly) {
            result("检测模式：事件已记录，不执行映射")
            return
        }
        if (!listening) { result("监听尚未就绪，本次仅记录"); return }
        if (key == null) { result("尚未映射此键码，本次仅记录"); return }
        if (event == SteeringEvent.RAW || event == SteeringEvent.NATIVE_DOUBLE) return
        val run = generation
        scope.launch {
            val allowed = menu.refresh()
            if (!active(run)) return@launch
            if (SystemClock.uptimeMillis() - time > 1000) { result("按键事件已过期，本次未执行"); return@launch }
            if (!allowed) { result("当前不是媒体菜单或状态未知，未执行按键动作"); return@launch }
            if (event != SteeringEvent.HOLD_END && !routes.allowsOneOs(key, SystemClock.uptimeMillis())) {
                gestures.reset(); result("本次按键已交由系统媒体路径处理"); return@launch
            }
            if (event != SteeringEvent.HOLD_END) routes.claimOneOs(key, SystemClock.uptimeMillis())
            gestures.accept(key, event, time)
        }
    }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(SteeringSettings.PREFIX)) handler.post {
            if (!closed) {
                cancelPending()
                if (key == null || key == SteeringSettings.ENABLED || key == SteeringSettings.RECONNECT) refresh()
            }
        }
    }
    init { preferences.registerOnSharedPreferenceChangeListener(changed); refresh() }
    private fun refresh() {
        listening = false; menuWatch?.cancel(); menuWatch = null; menu.reset()
        if (settings.enabled) connection.start()
        else { connection.stop(); status.value = status.value.copy(connection = "未开启车机按键适配", detail = "标准媒体按键仍由系统处理") }
    }
    fun cancelPending() { generation++; gestures.reset(); routes.clearOneOs() }
    fun result(message: String) { if (!closed) status.value = status.value.copy(lastAction = message) }
    fun onMediaButton(event: KeyEvent): Boolean {
        if (SteeringKey.fromMediaCode(event.keyCode) == null) return false
        val consumed = routes.standard(event.keyCode, event.deviceId, event.downTime, event.action, event.repeatCount,
            SystemClock.uptimeMillis(), !closed && listening && settings.enabled && !settings.detectOnly)
        if (!closed && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            status.value = status.value.copy(standardEvents = status.value.standardEvents + 1)
            diagnose("系统媒体键 ${event.keyCode}：${if (consumed) "已匹配 OneOS，去重" else "交由 Media3 处理"}")
        }
        return consumed
    }
    override fun close() {
        closed = true; listening = false; cancelPending(); menuWatch?.cancel(); menu.reset()
        preferences.unregisterOnSharedPreferenceChangeListener(changed)
        connection.stop()
        status.value = SteeringStatus(if (settings.enabled) "播放服务未运行" else "未开启车机按键适配", "打开播放器后恢复检测")
    }
}
