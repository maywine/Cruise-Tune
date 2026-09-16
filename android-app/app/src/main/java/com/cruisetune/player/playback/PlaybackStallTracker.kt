package com.cruisetune.player.playback

/** READY/BUFFERING flapping is not progress. Only the engine position renews this deadline. */
internal class PlaybackStallTracker(private val timeoutMs: Long = 10000, private val minimumProgressMs: Long = 250) {
    private var trackKey: String? = null
    private var checkpoint = 0L
    private var lastProgressAt = 0L
    private var reported = false

    fun update(key: String?, positionMs: Long, eligible: Boolean, nowMs: Long): Boolean {
        if (!eligible || key == null) { reset(); return false }
        if (key != trackKey) {
            trackKey = key; checkpoint = positionMs; lastProgressAt = nowMs; reported = false
            return false
        }
        if (positionMs - checkpoint >= minimumProgressMs) {
            checkpoint = positionMs; lastProgressAt = nowMs; reported = false
            return false
        }
        if (!reported && nowMs - lastProgressAt >= timeoutMs) {
            reported = true
            return true
        }
        return false
    }

    fun reset() { trackKey = null; reported = false }
}
