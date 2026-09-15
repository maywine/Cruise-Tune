package com.cruisetune.player.steering

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SteeringMenuGuardTest {
    @Test fun everyGestureRequiresMediaMenuAtReceiptAndExecution() = runBlocking {
        for (gesture in SteeringGesture.entries) {
            var current: Int? = 2
            val guard = SteeringMenuGuard({ current }) {}
            assertTrue(guard.refresh()); val ticket = guard.revision
            for (nonMedia in listOf(3, 0, 1, 4, null)) {
                current = nonMedia
                assertFalse("$gesture must not execute in menu $nonMedia", guard.permits(ticket))
            }
            current = 2
            assertFalse("Returning to media cannot resurrect the old gesture", guard.permits(ticket))
            assertTrue(guard.permits(guard.revision))
        }
    }
    @Test fun queryFailureIsUnknownAndNeverFallsBackToMedia() = runBlocking {
        var failed = false
        val guard = SteeringMenuGuard({ if (failed) throw SecurityException() else 2 }) {}
        assertTrue(guard.refresh()); val ticket = guard.revision
        failed = true; assertFalse(guard.permits(ticket)); assertNull(guard.index)
    }
    @Test fun resetInvalidatesAnInFlightSuccessfulMediaResponse() = runBlocking {
        val response = CompletableDeferred<Int?>()
        val guard = SteeringMenuGuard({ response.await() }) {}
        val read = async(start = CoroutineStart.UNDISPATCHED) { guard.refresh() }
        guard.reset(); response.complete(2)
        assertFalse(read.await()); assertNull(guard.index)
    }
    @Test fun olderRemoteResultCannotOverwriteNewerNonMediaObservation() = runBlocking {
        val slow = CompletableDeferred<Int?>(); var request = 0
        val guard = SteeringMenuGuard({ if (++request == 1) slow.await() else 3 }) {}
        val old = async(start = CoroutineStart.UNDISPATCHED) { guard.refresh() }
        assertFalse(guard.refresh()); slow.complete(2)
        assertFalse(old.await()); assertEquals(3, guard.index)
    }
    @Test fun menuChangeCancels350msSingleAndReturnToMediaStartsANewGesture() = runBlocking {
        var current = 2; var now = 0L; var pending: (() -> Unit)? = null
        val gestures = mutableListOf<SteeringGesture>()
        val recognizer = SteeringGestures({ now }, { _, action ->
            pending = action
            val cancel: () -> Unit = { pending = null }; cancel
        }) { _, gesture -> gestures += gesture }
        val guard = SteeringMenuGuard({ current }) { recognizer.reset() }
        assertTrue(guard.refresh())
        recognizer.accept(SteeringKey.RIGHT, SteeringEvent.SHORT)
        now = 200; current = 3; assertFalse(guard.refresh())
        now = 350; pending?.invoke(); assertTrue(gestures.isEmpty())
        current = 2; assertTrue(guard.refresh())
        recognizer.accept(SteeringKey.RIGHT, SteeringEvent.SHORT)
        now = 700; pending?.invoke(); assertEquals(listOf(SteeringGesture.SINGLE), gestures)
    }
}
