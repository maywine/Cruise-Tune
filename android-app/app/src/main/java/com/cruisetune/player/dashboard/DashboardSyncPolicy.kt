package com.cruisetune.player.dashboard

/**
 * Owns only the local publication lifecycle. It does not infer that a receiver displayed
 * anything or that another player has definitely taken ownership of the instrument cluster.
 */
internal class DashboardSyncPolicy {
    private var active: DashboardSnapshot? = null
    private var lastState: DashboardPlaybackState? = null
    private var generation = 0L

    fun onPlayback(input: DashboardInput): List<DashboardSnapshot> {
        if (input.invalidated) {
            invalidate()
            return emptyList()
        }
        val snapshot = input.snapshot
        return when (snapshot.state) {
            DashboardPlaybackState.PLAYING -> playing(snapshot)
            DashboardPlaybackState.BUFFERING -> buffering(snapshot)
            DashboardPlaybackState.PAUSED -> paused(snapshot)
        }
    }

    fun invalidate() {
        generation++
        active = null
        lastState = null
    }

    private fun playing(snapshot: DashboardSnapshot): List<DashboardSnapshot> {
        val previous = active
        val leaving = previous?.takeIf { it.trackId != snapshot.trackId && lastState != DashboardPlaybackState.PAUSED }
            ?.copy(state = DashboardPlaybackState.PAUSED)
        if (previous?.trackId != snapshot.trackId) generation++
        val current = snapshot.copy(generation = generation)
        active = current
        lastState = DashboardPlaybackState.PLAYING
        return listOfNotNull(leaving, current)
    }

    private fun buffering(snapshot: DashboardSnapshot): List<DashboardSnapshot> {
        val current = active ?: return emptyList()
        if (current.trackId != snapshot.trackId || lastState == DashboardPlaybackState.PAUSED) return emptyList()
        val paused = snapshot.copy(state = DashboardPlaybackState.PAUSED, generation = generation)
        active = paused
        lastState = DashboardPlaybackState.PAUSED
        return listOf(paused)
    }

    private fun paused(snapshot: DashboardSnapshot): List<DashboardSnapshot> {
        val current = active ?: return emptyList()
        if (current.trackId != snapshot.trackId) return emptyList()
        val paused = snapshot.copy(state = DashboardPlaybackState.PAUSED, generation = generation)
        active = null
        return if (lastState == DashboardPlaybackState.PAUSED) emptyList() else {
            lastState = DashboardPlaybackState.PAUSED
            listOf(paused)
        }
    }
}
