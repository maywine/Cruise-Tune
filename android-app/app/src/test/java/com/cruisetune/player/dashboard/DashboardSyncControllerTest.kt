package com.cruisetune.player.dashboard

import android.app.Application
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        override fun inspect() = DashboardEndpoint(DashboardEndpointKind.READY, "测试接收器已准备", "1.0", 1000, true)
        override fun dispatch(intent: Intent): DashboardDispatch { sent += Intent(intent); return DashboardDispatch.Dispatched }
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

    @Test fun disablingSyncClearsTheLastPlayingState() = runTest {
        val context = RuntimeEnvironment.getApplication() as Application
        val preferences = context.getSharedPreferences("dashboard-disable", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(DashboardSettings.ENABLED, true).commit()
        }
        val transport = FakeTransport()
        val status = MutableStateFlow(DashboardStatus())
        var disabledCount = 0
        val controller = DashboardSyncController(this, DashboardSettings(preferences), preferences, transport, status,
            elapsedMs = { testScheduler.currentTime }, ioDispatcher = StandardTestDispatcher(testScheduler),
            onDisabled = { disabledCount++ })
        advanceUntilIdle()
        controller.onPlayback(DashboardInput(snapshot(DashboardPlaybackState.PLAYING)))
        advanceTimeBy(200); advanceUntilIdle()
        preferences.edit().putBoolean(DashboardSettings.ENABLED, false).commit()
        advanceUntilIdle()
        assertEquals(listOf(3, 2), transport.sent.map { it.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) })
        assertEquals(DashboardStatusKind.DISABLED, status.value.kind)
        assertEquals(1, disabledCount)
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
