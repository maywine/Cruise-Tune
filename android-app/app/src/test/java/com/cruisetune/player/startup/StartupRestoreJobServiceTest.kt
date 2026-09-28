package com.cruisetune.player.startup

import android.app.Application
import android.app.job.JobParameters
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.awaitCancellation
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

    @Test fun disablingStartupWhileConnectingFinishesWithoutRetry() {
        val owner = Robolectric.buildService(StartupRestoreJobService::class.java).create()
        var closed = false
        owner.get().connect = { object : StartupPlaybackConnection {
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
}
