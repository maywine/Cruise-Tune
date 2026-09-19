package com.cruisetune.player.dashboard

import org.junit.Assert.*
import org.junit.Test

class DashboardSyncPolicyTest {
    private fun snapshot(id: String, state: DashboardPlaybackState) = DashboardSnapshot(
        trackId = id, sourcePackage = "com.cruisetune.player", title = id, artist = "Artist", album = "Album",
        state = state, durationMs = 120_000, positionMs = 1_000,
    )

    @Test fun restoredPausedQueueDoesNotClaimTheInstrument() {
        val policy = DashboardSyncPolicy()
        assertTrue(policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PAUSED))).isEmpty())
    }

    @Test fun playBufferResumeAndPauseOnlyPublishMeaningfulTransitions() {
        val policy = DashboardSyncPolicy()
        val playing = policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PLAYING))).single()
        assertEquals(DashboardPlaybackState.PLAYING, playing.state)
        assertEquals(1L, playing.generation)
        assertEquals(DashboardPlaybackState.PAUSED, policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.BUFFERING))).single().state)
        assertTrue(policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.BUFFERING))).isEmpty())
        assertEquals(DashboardPlaybackState.PLAYING, policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PLAYING))).single().state)
        assertEquals(DashboardPlaybackState.PAUSED, policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PAUSED))).single().state)
        assertTrue(policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PAUSED))).isEmpty())
    }

    @Test fun changingSongPausesTheLastPublishedSongBeforeTheNewOne() {
        val policy = DashboardSyncPolicy()
        policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PLAYING)))
        val output = policy.onPlayback(DashboardInput(snapshot("second", DashboardPlaybackState.PLAYING)))
        assertEquals(listOf("first", "second"), output.map { it.trackId })
        assertEquals(listOf(DashboardPlaybackState.PAUSED, DashboardPlaybackState.PLAYING), output.map { it.state })
        assertTrue(output[1].generation > output[0].generation)
    }

    @Test fun invalidationDropsLocalPublicationWithoutInventingAStopProtocol() {
        val policy = DashboardSyncPolicy()
        policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PLAYING)))
        assertTrue(policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PLAYING), invalidated = true)).isEmpty())
        assertTrue(policy.onPlayback(DashboardInput(snapshot("first", DashboardPlaybackState.PAUSED))).isEmpty())
    }
}
