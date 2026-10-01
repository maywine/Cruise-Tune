package com.cruisetune.player.startup

import android.app.Application
import android.app.job.JobParameters
import android.content.Context
import android.os.Looper
import com.cruisetune.player.dashboard.DashboardStatus
import com.cruisetune.player.dashboard.DashboardStatusKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
@androidx.media3.common.util.UnstableApi
class StartupRestoreJobServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val preferences get() = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private fun parameters(): JobParameters = Shadow.newInstanceOf(JobParameters::class.java)
    @Before fun setup() { preferences.edit().clear().putBoolean(StartupSettings.ENABLED, true).commit() }

    @Test fun finishesWithoutActivityAndClosesItsConnectionAfterEmptyRestore() {
        val owner = Robolectric.buildService(StartupRestoreJobService::class.java).create()
        var closed = false
        owner.get().connect = { object : StartupPlaybackConnection {
            override suspend fun connect() = Unit
            override suspend fun restore(forceDashboard: Boolean) = StartupRestoreReply(StartupRestoreState.EMPTY, detail = "没有可恢复的播放队列")
            override fun close() { closed = true }
        } }
        try {
            assertTrue(owner.get().onStartJob(parameters()))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(closed)
            assertTrue(shadowOf(owner.get()).isJobFinished)
            assertFalse(shadowOf(owner.get()).isRescheduleNeeded)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { owner.destroy() }
    }

    @Test fun systemStopCancelsConnectionAndBoundsReschedulingToThreeAttempts() {
        repeat(3) { attempt ->
            val owner = Robolectric.buildService(StartupRestoreJobService::class.java).create()
            var closed = false
            owner.get().connect = { object : StartupPlaybackConnection {
                override suspend fun connect() = Unit
                override suspend fun restore(forceDashboard: Boolean): StartupRestoreReply = awaitCancellation()
                override fun close() { closed = true }
            } }
            val params = parameters()
            try {
                assertTrue(owner.get().onStartJob(params))
                shadowOf(Looper.getMainLooper()).idle()
                // start/stop arguments cross Binder separately and need not be the same instance.
                assertEquals(attempt < 2, owner.get().onStopJob(parameters()))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
                assertTrue(closed)
                assertFalse("Stopped jobs must not also call jobFinished", shadowOf(owner.get()).isJobFinished)
            } finally { owner.destroy() }
        }
    }

    @Test fun anExhaustedJobCannotStartAFourthAttempt() {
        val owner = Robolectric.buildService(StartupRestoreJobService::class.java).create()
        var attempts = 0
        owner.get().connect = { object : StartupPlaybackConnection {
            override suspend fun connect() = Unit
            override suspend fun restore(forceDashboard: Boolean): StartupRestoreReply {
                attempts++
                throw IllegalStateException("Synthetic connection failure")
            }
            override fun close() = Unit
        } }
        try {
            repeat(3) { attempt ->
                assertTrue(owner.get().onStartJob(parameters()))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(attempt < 2, shadowOf(owner.get()).isRescheduleNeeded)
            }
            assertFalse(owner.get().onStartJob(parameters()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, attempts)
        } finally { owner.destroy() }
    }

    @Test fun disablingStartupWhileConnectingFinishesWithoutRetry() {
        val owner = Robolectric.buildService(StartupRestoreJobService::class.java).create()
        var closed = false
        owner.get().connect = { object : StartupPlaybackConnection {
            override suspend fun connect() = Unit
            override suspend fun restore(forceDashboard: Boolean): StartupRestoreReply = awaitCancellation()
            override fun close() { closed = true }
        } }
        try {
            assertTrue(owner.get().onStartJob(parameters()))
            shadowOf(Looper.getMainLooper()).idle()
            preferences.edit().putBoolean(StartupSettings.ENABLED, false).commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(closed)
            assertTrue(shadowOf(owner.get()).isJobFinished)
            assertFalse(shadowOf(owner.get()).isRescheduleNeeded)
        } finally { owner.destroy() }
    }

    @Test fun timeoutsPersistTheStageThatActuallyFailed() {
        for (blocked in listOf(StartupStage.CONNECTING, StartupStage.RESTORING)) {
            setup()
            val owner = Robolectric.buildService(StartupRestoreJobService::class.java).create()
            var closed = false
            owner.get().connect = { object : StartupPlaybackConnection {
                override suspend fun connect() { if (blocked == StartupStage.CONNECTING) awaitCancellation() }
                override suspend fun restore(forceDashboard: Boolean): StartupRestoreReply = awaitCancellation()
                override fun close() { closed = true }
            } }
            try {
                assertTrue(owner.get().onStartJob(parameters()))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(blocked, StartupSettings(preferences).stage)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
                assertTrue(closed)
                assertTrue(shadowOf(owner.get()).isJobFinished)
                assertTrue(shadowOf(owner.get()).isRescheduleNeeded)
                val saved = StartupSettings(preferences)
                assertEquals(StartupStage.RETRY_WAIT, saved.stage)
                assertTrue(saved.diagnostic().contains("${blocked.description}超时"))
                assertTrue(saved.diagnostic().contains("第 1/3 次"))
            } finally { owner.destroy() }
        }
    }

    @Test fun dashboardObservationsArePersistedOnlyWhileTheBootJobIsActive() {
        val owner = Robolectric.buildService(StartupRestoreJobService::class.java).create()
        val status = MutableStateFlow(DashboardStatus())
        owner.get().dashboardUpdates = { status }
        var closed = false
        owner.get().connect = { object : StartupPlaybackConnection {
            override suspend fun connect() = Unit
            override suspend fun restore(forceDashboard: Boolean) = StartupRestoreReply(StartupRestoreState.RESTORED, true, "已恢复上次队列，保持暂停")
            override fun close() { closed = true }
        } }
        val params = parameters()
        try {
            assertTrue(owner.get().onStartJob(params))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(StartupStage.SYNCING, StartupSettings(preferences).stage)
            status.value = DashboardStatus(DashboardStatusKind.READY, "已发送，需在仪表媒体菜单确认", sentCount = 2)
            shadowOf(Looper.getMainLooper()).idle()
            val report = StartupSettings(preferences).diagnostic()
            assertTrue(report.contains("启动窗口仪表记录"))
            assertTrue(report.contains("记录时本进程已发送 2 次（无接收回执）"))
            owner.get().onStopJob(params)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(closed)
            val stopped = StartupSettings(preferences).diagnostic()
            status.value = DashboardStatus(DashboardStatusKind.READY, "Manual UI update", sentCount = 99)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(stopped, StartupSettings(preferences).diagnostic())
        } finally { owner.destroy() }
    }
}
