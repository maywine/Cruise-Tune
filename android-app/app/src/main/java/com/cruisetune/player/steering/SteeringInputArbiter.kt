package com.cruisetune.player.steering

/** First observed usable route owns a press. Registration alone never consumes a media key. */
internal class SteeringInputArbiter {
    private data class Press(val code: Int, val device: Int, val downTime: Long)
    private data class Down(val key: SteeringKey, val consumed: Boolean, val at: Long)
    private val presses = linkedMapOf<Press, Down>()
    private val system = mutableMapOf<SteeringKey, Long>()
    private val oneOs = mutableMapOf<SteeringKey, Long>()
    private fun recent(now: Long, time: Long?) = time != null && now - time in 0..350
    fun clearOneOs() { oneOs.clear() }
    fun standard(code: Int, device: Int, downTime: Long, action: Int, repeat: Int, now: Long, allowDedupe: Boolean): Boolean {
        val key = SteeringKey.fromMediaCode(code) ?: return false
        presses.entries.removeAll { now - it.value.at > 30_000 }
        val press = Press(code, device, downTime)
        if (action == 1) {
            val consumed = presses.remove(press)?.consumed == true
            if (!consumed) system[key] = now
            return consumed
        }
        if (action != 0) return false
        if (repeat > 0) return presses[press]?.consumed == true
        val consumed = allowDedupe && recent(now, oneOs[key])
        if (presses.size >= 16) presses.remove(presses.keys.first())
        presses[press] = Down(key, consumed, now)
        if (!consumed) system[key] = now
        return consumed
    }
    fun allowsOneOs(key: SteeringKey, now: Long): Boolean =
        !recent(now, system[key]) && presses.values.none { it.key == key && !it.consumed && now - it.at in 0..30_000 }
    fun claimOneOs(key: SteeringKey, now: Long) { oneOs[key] = now }
}
