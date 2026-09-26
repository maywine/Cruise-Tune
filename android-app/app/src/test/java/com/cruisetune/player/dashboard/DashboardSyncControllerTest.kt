package com.cruisetune.player.dashboard

import android.app.Application
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DashboardSyncControllerTest {
    private class FakeTransport : DashboardTransport {
        val sent = mutableListOf<Intent>()
        var failuresRemaining = 0
        var attempts = 0
        override fun inspect() = DashboardEndpoint(DashboardEndpointKind.READY, "测试接收器已准备", "1.0", 1000, true)
        override fun dispatch(intent: Intent): DashboardDispatch {
            attempts++
            if (failuresRemaining > 0) { failuresRemaining--; return DashboardDispatch.Failed("Temporary failure", true) }
            sent += Intent(intent); return DashboardDispatch.Dispatched
        }
    }

    private fun snapshot(state: DashboardPlaybackState, position: Long = 1_000) = DashboardSnapshot(
        trackId = "song-a", sourcePackage = "com.cruisetune.player", title = "Song A", artist = "Artist",
        album = "Album", state = state, durationMs = 120_000, positionMs = position,
    )

    @Test fun onlyActualPlaybackClaimsTheReceiverAndInvalidationStopsFutureTicks() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-controller", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
        }
        val transport = FakeTransport()
        val status = MutableStateFlow(DashboardStatus())
        val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport, status,
            elapsedMs = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED)))
        advanceUntilIdle()
        assertTrue(transport.sent.isEmpty())

        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING)))
        advanceTimeBy(200)
        advanceUntilIdle()
        assertEquals(1, transport.sent.size)
        assertEquals(3, transport.sent.single().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))

        controller.invalidate("熄屏")
        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING, 4_000), invalidated = true, invalidationReason = "熄屏"), tick = true)
        advanceTimeBy(2_000)
        advanceUntilIdle()
        assertEquals(2, transport.sent.size)
        assertEquals(2, transport.sent.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
        assertEquals(DashboardStatusKind.WAITING, status.value.kind)
        controller.close()
    }

    @Test fun startupPendingTrackIsPublishedAsPausedUntilPlaybackStarts() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-pending", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
        }
        val transport = FakeTransport()
        val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport, MutableStateFlow(DashboardStatus()),
            elapsedMs = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        controller.onPendingPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED)))
        advanceUntilIdle()
        assertEquals(listOf(2), transport.sent.map { it.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) })

        repeat(5) {
            controller.onPendingPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED)))
            advanceTimeBy(2_000); runCurrent()
        }
        assertEquals("Idle updates must not republish a successful snapshot", 1, transport.sent.size)
        controller.requestResend(); advanceUntilIdle()
        assertEquals("Manual recheck must work before playback", 2, transport.sent.size)

        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING)))
        advanceTimeBy(200); advanceUntilIdle()
        assertEquals(listOf(2, 2, 3), transport.sent.map { it.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) })
        controller.close()
    }

    @Test fun pendingPublicationWaitsForInspectionAndInvalidationCancelsIt() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-pending-inspection", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
        }
        val io = StandardTestDispatcher(testScheduler)
        for (invalidated in listOf(false, true)) {
            val transport = FakeTransport()
            val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport,
                MutableStateFlow(DashboardStatus()), elapsedMs = { testScheduler.currentTime }, ioDispatcher = io)
            controller.onPendingPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED)))
            if (invalidated) controller.invalidate("熄屏")
            advanceUntilIdle()
            assertEquals(if (invalidated) 0 else 1, transport.sent.size)
            controller.close()
        }
    }

    @Test fun pendingPublicationRetriesStayBoundedAcrossIdleUpdatesAndAllowManualRetry() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-pending-retry", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
        }
        val transport = FakeTransport().apply { failuresRemaining = 4 }
        val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport,
            MutableStateFlow(DashboardStatus()), elapsedMs = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler))
        controller.onPendingPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED)))
        runCurrent()
        repeat(20) {
            advanceTimeBy(2_000); runCurrent()
            controller.onPendingPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED)))
            runCurrent()
        }
        assertEquals("One initial dispatch and three retries", 4, transport.attempts)
        assertTrue(transport.sent.isEmpty())
        controller.requestResend(); advanceUntilIdle()
        assertEquals(5, transport.attempts)
        assertEquals(2, transport.sent.single().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
        controller.close()
    }

    @Test fun pendingRetryDoesNotResurrectAfterPlaybackSelectionOrDisablingSync() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-pending-cancel", Context.MODE_PRIVATE)
        for (cancel in listOf("screenOff", "disabled", "selection", "playing", "close")) {
            preferences.edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
            val transport = FakeTransport().apply { failuresRemaining = 1 }
            val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport,
                MutableStateFlow(DashboardStatus()), elapsedMs = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler))
            controller.onPendingPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED)))
            runCurrent()
            assertEquals(1, transport.attempts)
            when (cancel) {
                "screenOff" -> { controller.invalidate("熄屏"); controller.requestResend() }
                "disabled" -> preferences.edit().putBoolean(DashboardSettings.ENABLED, false).commit()
                "selection" -> controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PAUSED).copy(trackId = "other")))
                "playing" -> controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING)))
                "close" -> controller.close()
            }
            advanceUntilIdle()
            assertEquals(cancel, if (cancel == "playing") 2 else 1, transport.attempts)
            assertTrue(cancel, transport.sent.all { it.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) == 3 })
            controller.close()
        }
    }

    @Test fun disablingSyncClearsTheLastPlayingState() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-disable", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
        }
        val transport = FakeTransport()
        val status = MutableStateFlow(DashboardStatus())
        val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport, status,
            elapsedMs = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING)))
        advanceTimeBy(200); advanceUntilIdle()
        preferences.edit().putBoolean(DashboardSettings.ENABLED, false).commit()
        advanceUntilIdle()
        assertEquals(listOf(3, 2), transport.sent.map { it.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) })
        assertEquals(DashboardStatusKind.DISABLED, status.value.kind)
        controller.close()
    }

    @Test fun bufferingPublishesOnePauseThenActualPlaybackResumes() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-buffer", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
        }
        val transport = FakeTransport()
        val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport,
            MutableStateFlow(DashboardStatus()), elapsedMs = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING)))
        advanceTimeBy(200); advanceUntilIdle()
        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.BUFFERING)))
        advanceUntilIdle()
        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.BUFFERING)))
        advanceUntilIdle()
        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING, 1_500)))
        advanceTimeBy(200); advanceUntilIdle()
        assertEquals(listOf(3, 2, 3), transport.sent.map { it.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) })
        controller.close()
    }
}
