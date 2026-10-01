package com.cruisetune.player.startup

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupRestorationTest {
    private class Connection(
        private val connecting: suspend () -> Unit = {},
        val respond: suspend (Boolean) -> StartupRestoreReply,
    ) : StartupPlaybackConnection {
        var closed = false
        override suspend fun connect() = connecting()
        override suspend fun restore(forceDashboard: Boolean) = respond(forceDashboard)
        override fun close() { closed = true }
    }
    private val pending = StartupRestoreReply(StartupRestoreState.RESTORED, true, "Restored")

    @Test fun holdsAHeadlessConnectionForTheBoundedVehicleStartupWindow() = runTest {
        val calls = mutableListOf<Pair<Long, Boolean>>()
        val stages = mutableListOf<StartupStage>()
        val connection = Connection { force -> calls += testScheduler.currentTime to force; pending }
        StartupRestoration(connection, { true }, {}, stages::add).run()
        assertEquals(listOf(0L to false, 5_000L to true, 15_000L to true, 30_000L to true, 60_000L to false), calls)
        assertEquals(listOf(StartupStage.CONNECTING, StartupStage.RESTORING, StartupStage.SYNCING), stages.distinct())
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

    @Test fun connectionAndRestorationHaveIndependentTimeoutBudgets() = runTest {
        val stages = mutableListOf<Pair<Long, StartupStage>>()
        val connection = Connection(connecting = { delay(6_000L) }) {
            delay(6_000L)
            StartupRestoreReply(StartupRestoreState.EMPTY)
        }
        StartupRestoration(connection, { true }, {}, { stages += testScheduler.currentTime to it }).run()
        assertEquals(listOf(0L to StartupStage.CONNECTING, 6_000L to StartupStage.RESTORING), stages)
        assertEquals(12_000L, testScheduler.currentTime)
        assertTrue(connection.closed)
    }

    @Test fun connectingTimeoutNeverRequestsRestoration() = runTest {
        val stages = mutableListOf<StartupStage>()
        var requests = 0
        val connection = Connection(connecting = { awaitCancellation() }) { requests++; pending }
        val error = runCatching { StartupRestoration(connection, { true }, {}, stages::add).run() }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(listOf(StartupStage.CONNECTING), stages)
        assertEquals(0, requests)
        assertEquals(10_000L, testScheduler.currentTime)
        assertTrue(connection.closed)
    }

    @Test fun disablingWhileConnectingPreventsRestoration() = runTest {
        var enabled = true
        var requests = 0
        val connection = Connection(connecting = { delay(1_000L); enabled = false }) { requests++; pending }
        StartupRestoration(connection, { enabled }, {}).run()
        assertEquals(0, requests)
        assertTrue(connection.closed)
    }

    @Test fun disabledStartupDoesNotEvenConnect() = runTest {
        var connected = false
        var requested = false
        val connection = Connection(connecting = { connected = true }) { requested = true; pending }
        StartupRestoration(connection, { false }, {}).run()
        assertFalse(connected)
        assertFalse(requested)
        assertTrue(connection.closed)
    }
}
