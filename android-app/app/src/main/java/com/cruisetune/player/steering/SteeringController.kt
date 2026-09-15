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
    private val execute: (SteeringAction, () -> Boolean) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val settings = SteeringSettings(preferences)
    private var closed = false
    private var listening = false
    private var generation = 0
    private var menuWatch: Job? = null
    private val gestures: SteeringGestures = SteeringGestures(SystemClock::uptimeMillis, { delay, action ->
        val runnable = Runnable { action() }; handler.postDelayed(runnable, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(runnable) }; cancel
    }, ::gesture)
    private val menu: SteeringMenuGuard = SteeringMenuGuard({ connection.controlIndex() }) { index ->
        gestures.reset()
        status.value = status.value.copy(menu = when (index) {
            2 -> "当前为媒体菜单（2）"
            3 -> "当前为电话菜单（3），不执行播放动作"
            null -> "无法确认按键菜单，不执行播放动作"
            else -> "当前为其他菜单（$index），不执行播放动作"
        })
    }
    private val connection: OneOsConnection = OneOsConnection(context, scope, ::connectionChanged, receive = ::receive)
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
    private fun receive(code: Int, event: SteeringEvent, time: Long) {
        if (closed || !listening || !settings.enabled) return
        // Bounded diagnostic: only the latest key/event and menu, never VIN or system logs.
        status.value = status.value.copy(lastEvent = "键码 $code · ${event.name}")
        val key = SteeringKey.fromCode(code) ?: return
        if (event == SteeringEvent.RAW || event == SteeringEvent.NATIVE_DOUBLE) return
        val run = generation
        scope.launch {
            val allowed = menu.refresh()
            if (!active(run) || SystemClock.uptimeMillis() - time > 1000) return@launch
            if (!allowed) { result("当前不是媒体菜单或状态未知，未执行按键动作"); return@launch }
            gestures.accept(key, event, time)
        }
    }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith(SteeringSettings.PREFIX)) handler.post {
            if (!closed) {
                cancelPending()
                if (key == null || key == SteeringSettings.ENABLED) refresh()
            }
        }
    }
    init { preferences.registerOnSharedPreferenceChangeListener(changed); refresh() }
    private fun refresh() {
        listening = false; menuWatch?.cancel(); menuWatch = null; menu.reset()
        if (settings.enabled) connection.start()
        else { connection.stop(); status.value = SteeringStatus() }
    }
    fun cancelPending() { generation++; gestures.reset() }
    fun result(message: String) { if (!closed) status.value = status.value.copy(lastAction = message) }
    // In OneOS mode these hardware intents cannot bypass the menu gate or execute a second time.
    fun ownsMediaButton(keyCode: Int): Boolean = listening && settings.enabled &&
        keyCode in intArrayOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    override fun close() {
        closed = true; listening = false; cancelPending(); menuWatch?.cancel(); menu.reset()
        preferences.unregisterOnSharedPreferenceChangeListener(changed)
        connection.stop()
        status.value = SteeringStatus(if (settings.enabled) "播放服务未运行" else "未开启车机按键适配", "打开播放器后恢复检测")
    }
}
