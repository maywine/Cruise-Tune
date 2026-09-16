package com.cruisetune.player.steering

import org.junit.Assert.*
import org.junit.Test

class SteeringGesturesTest {
    private var time = 0L
    private val pending = linkedMapOf<Int, Pair<Long, () -> Unit>>()
    private var sequence = 0
    private val actions = mutableListOf<Pair<SteeringKey, SteeringGesture>>()
    private val recognizer = SteeringGestures({ time }, { delay, action ->
        val id = ++sequence; pending[id] = time + delay to action
        val cancel: () -> Unit = { pending.remove(id); Unit }; cancel
    }) { key, gesture -> actions += key to gesture }
    private fun event(event: SteeringEvent, key: SteeringKey = SteeringKey.RIGHT) = recognizer.accept(key, event)
    private fun advance(ms: Long) {
        val target = time + ms
        while (true) {
            val next = pending.entries.filter { it.value.first <= target }.minByOrNull { it.value.first } ?: break
            time = next.value.first; pending.remove(next.key); next.value.second()
        }
        time = target
    }
    @Test fun singleWaitsExactly350ms() {
        event(SteeringEvent.SHORT); advance(349); assertTrue(actions.isEmpty())
        advance(1); assertEquals(listOf(SteeringKey.RIGHT to SteeringGesture.SINGLE), actions)
    }
    @Test fun secondClickCancelsSingleAndNativeDoubleDoesNotDuplicateIt() {
        event(SteeringEvent.SHORT); advance(200); event(SteeringEvent.SHORT)
        event(SteeringEvent.NATIVE_DOUBLE); advance(1000)
        assertEquals(listOf(SteeringKey.RIGHT to SteeringGesture.DOUBLE), actions)
    }
    @Test fun duplicateCallbacksWithin80msDoNotBecomeDoubleClick() {
        event(SteeringEvent.SHORT); advance(30); event(SteeringEvent.SHORT); advance(20); event(SteeringEvent.SHORT)
        advance(500); assertEquals(listOf(SteeringKey.RIGHT to SteeringGesture.SINGLE), actions)
    }
    @Test fun boundaryClickStartsAnotherSingle() {
        event(SteeringEvent.SHORT); advance(350); event(SteeringEvent.SHORT); advance(350)
        assertEquals(listOf(SteeringGesture.SINGLE, SteeringGesture.SINGLE), actions.map { it.second })
    }
    @Test fun twoKeysAreIndependent() {
        event(SteeringEvent.SHORT); advance(100); event(SteeringEvent.SHORT, SteeringKey.LEFT); advance(350)
        assertEquals(listOf(SteeringKey.RIGHT, SteeringKey.LEFT), actions.map { it.first })
    }
    @Test fun longStartAndTriggerExecuteOnceAndCancelPendingSingle() {
        event(SteeringEvent.SHORT); advance(100); event(SteeringEvent.HOLD_START)
        event(SteeringEvent.LONG); advance(500); event(SteeringEvent.LONG); event(SteeringEvent.HOLD_END)
        event(SteeringEvent.SHORT); advance(299); event(SteeringEvent.SHORT); advance(1000)
        assertEquals(listOf(SteeringGesture.LONG), actions.map { it.second })
    }
    @Test fun anotherPressAfterLongReleaseIsNotLost() {
        event(SteeringEvent.HOLD_START); event(SteeringEvent.LONG); event(SteeringEvent.HOLD_END)
        advance(301); event(SteeringEvent.SHORT); advance(350)
        assertEquals(listOf(SteeringGesture.LONG, SteeringGesture.SINGLE), actions.map { it.second })
    }
    @Test fun triggerWithoutHoldStartIsOneLongAndSuppressesReleaseClick() {
        event(SteeringEvent.LONG); advance(20); event(SteeringEvent.LONG); event(SteeringEvent.SHORT)
        advance(1000); assertEquals(1, actions.size)
        event(SteeringEvent.SHORT); advance(350); assertEquals(SteeringGesture.SINGLE, actions.last().second)
    }
    @Test fun disconnectScreenOffOrMappingChangeDropsPendingActions() {
        event(SteeringEvent.SHORT); recognizer.reset(); advance(1000); assertTrue(actions.isEmpty())
        event(SteeringEvent.SHORT); advance(350); assertEquals(1, actions.size)
    }
    @Test fun rawDownUpIsNotASecondExecutionPath() {
        event(SteeringEvent.RAW); event(SteeringEvent.RAW); advance(500); assertTrue(actions.isEmpty())
        event(SteeringEvent.SHORT); advance(350); assertEquals(1, actions.size)
    }
    @Test fun binderArrivalTimesSurviveABusyMainThread() {
        time = 600
        recognizer.accept(SteeringKey.RIGHT, SteeringEvent.SHORT, 100)
        recognizer.accept(SteeringKey.RIGHT, SteeringEvent.SHORT, 300)
        advance(1000)
        assertEquals(listOf(SteeringKey.RIGHT to SteeringGesture.DOUBLE), actions)
    }
    @Test fun lateLongTriggerAfterReleaseIsNotAnotherLongPress() {
        event(SteeringEvent.HOLD_START); advance(2000); event(SteeringEvent.HOLD_END)
        advance(10); event(SteeringEvent.LONG)
        assertEquals(listOf(SteeringKey.RIGHT to SteeringGesture.LONG), actions)
    }
}
