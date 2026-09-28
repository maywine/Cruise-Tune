package com.cruisetune.player.startup

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupRestorationTest {
    private class Connection(val respond: suspend (Boolean) -> StartupRestoreReply) : StartupPlaybackConnection {
        var closed = false
        override suspend fun restore(forceDashboard: Boolean) = respond(forceDashboard)
        override fun close() { closed = true }
    }
    private val pending = StartupRestoreReply(StartupRestoreState.RESTORED, true, "Restored")

    @Test fun holdsAHeadlessConnectionForTheBoundedVehicleStartupWindow() = runTest {
        val calls = mutableListOf<Pair<Long, Boolean>>()
        val connection = Connection { force -> calls += testScheduler.currentTime to force; pending }
        StartupRestoration(connection, { true }, {}).run()
        assertEquals(listOf(0L to false, 5_000L to true, 15_000L to true, 30_000L to true, 60_000L to false), calls)
        assertTrue(connection.closed)
    }

    @Test fun userPlaybackOrQueueChangeEndsBootResends() = runTest {
        var calls = 0
        val connection = Connection { if (++calls == 1) pending else StartupRestoreReply(StartupRestoreState.ACTIVE) }
        StartupRestoration(connection, { true }, {}).run()
        assertEquals(2, calls)
        assertEquals(5_000L, testScheduler.currentTime)
        assertTrue(connection.closed)
    }

    @Test fun emptyQueueOrDisabledDashboardNeedsNoKeepAliveWindow() = runTest {
        for (reply in listOf(StartupRestoreReply(StartupRestoreState.EMPTY), StartupRestoreReply(StartupRestoreState.RESTORED))) {
            var calls = 0
            val connection = Connection { calls++; reply }
            StartupRestoration(connection, { true }, {}).run()
            assertEquals(1, calls)
            assertTrue(connection.closed)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun disablingStartupPreventsAnyFurtherRequest() = runTest {
        var enabled = true
        var calls = 0
        val connection = Connection { calls++; pending }
        val job = launch { StartupRestoration(connection, { enabled }, {}).run() }
        runCurrent()
        enabled = false
        advanceUntilIdle()
        job.join()
        assertEquals(1, calls)
        assertTrue(connection.closed)
    }

    @Test fun systemCancellationReleasesTheControllerAndStopsResends() = runTest {
        var calls = 0
        val connection = Connection { calls++; pending }
        val job = launch { StartupRestoration(connection, { true }, {}).run() }
        runCurrent(); job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(connection.closed)
    }

    @Test fun unresponsiveServiceTimesOutAndReleasesTheConnection() = runTest {
        val connection = Connection { awaitCancellation() }
        val error = runCatching { StartupRestoration(connection, { true }, {}).run() }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(10_000L, testScheduler.currentTime)
        assertTrue(connection.closed)
    }
}
