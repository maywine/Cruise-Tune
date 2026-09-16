package com.cruisetune.player.steering

enum class SteeringEvent(val label: String) {
    RAW("原始事件"), SHORT("单击回调"), HOLD_START("长按开始"), HOLD_END("长按结束"), LONG("长按触发"), NATIVE_DOUBLE("双击回调")
}

/** Main-thread state machine. Native double notifications are informational: two SHORTs own double detection. */
internal class SteeringGestures(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val emit: (SteeringKey, SteeringGesture) -> Unit,
) {
    companion object { const val DOUBLE_WINDOW_MS = 350L }
    private class State {
        var lastShort: Long? = null
        var firstShort: Long? = null
        var cancel: (() -> Unit)? = null
        var holding = false
        var longSent = false
        var release: Long? = null
        var lastLong: Long? = null
    }
    private val states = mutableMapOf<SteeringKey, State>()
    fun reset() { states.values.forEach { it.cancel?.invoke() }; states.clear() }
    fun accept(key: SteeringKey, event: SteeringEvent, time: Long = now()) {
        val state = states.getOrPut(key) { State() }
        fun cancelSingle() { state.cancel?.invoke(); state.cancel = null; state.firstShort = null }
        fun longPress() {
            cancelSingle()
            if (!state.longSent && (state.lastLong == null || time - state.lastLong!! >= 300)) {
                state.longSent = true; state.lastLong = time
                emit(key, SteeringGesture.LONG)
            }
        }
        when (event) {
            SteeringEvent.RAW, SteeringEvent.NATIVE_DOUBLE -> Unit
            SteeringEvent.HOLD_START -> { state.holding = true; longPress() }
            SteeringEvent.LONG -> {
                if (!state.holding && state.release?.let { time - it < 300 } == true) return
                longPress(); if (!state.holding) state.release = time
            }
            SteeringEvent.HOLD_END -> {
                cancelSingle(); state.holding = false; state.longSent = false; state.release = time
            }
            SteeringEvent.SHORT -> {
                if (state.holding || state.release?.let { time - it < 300 } == true) return
                if (state.lastShort?.let { time - it < 80 } == true) return
                state.lastShort = time; state.longSent = false
                val first = state.firstShort
                if (first != null && time - first < DOUBLE_WINDOW_MS) {
                    cancelSingle(); emit(key, SteeringGesture.DOUBLE)
                } else {
                    // Normally the timer has fired. A delayed main thread must not lose the first press.
                    if (first != null) { cancelSingle(); emit(key, SteeringGesture.SINGLE) }
                    state.firstShort = time
                    state.cancel = schedule((time + DOUBLE_WINDOW_MS - now()).coerceAtLeast(0)) {
                        state.cancel = null; state.firstShort = null
                        emit(key, SteeringGesture.SINGLE)
                    }
                }
            }
        }
    }
}
