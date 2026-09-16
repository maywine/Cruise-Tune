package com.cruisetune.player.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackStallTrackerTest {
    @Test fun noProgressTriggersOnceWithoutRequiringAPlayerError() {
        val tracker = PlaybackStallTracker()
        assertFalse(tracker.update("a", 0, true, 0))
        assertFalse(tracker.update("a", 0, true, 9999))
        assertTrue(tracker.update("a", 0, true, 10000))
        assertFalse(tracker.update("a", 0, true, 60000))
    }
    @Test fun realProgressRenewsTheDeadlineButSmallPositionJitterDoesNot() {
        val tracker = PlaybackStallTracker()
        tracker.update("a", 0, true, 0)
        assertFalse(tracker.update("a", 120, true, 4000))
        assertFalse(tracker.update("a", 0, true, 8000))
        assertTrue(tracker.update("a", 120, true, 10000))
        assertFalse(tracker.update("a", 500, true, 12000))
        assertFalse(tracker.update("a", 750, true, 20000))
        assertTrue(tracker.update("a", 750, true, 30000))
    }
    @Test fun nextThenPreviousStartsAFreshWindowForTheOriginalSong() {
        val tracker = PlaybackStallTracker()
        tracker.update("a", 0, true, 0)
        assertFalse(tracker.update("a", 0, true, 8000))
        assertFalse(tracker.update("b", 0, true, 9000))
        assertFalse(tracker.update("b", 2000, true, 11000))
        assertFalse(tracker.update("a", 0, true, 12000))
        assertFalse(tracker.update("a", 0, true, 21999))
        assertTrue(tracker.update("a", 0, true, 22000))
    }
    @Test fun pauseFocusLossAndIneligibleNetworkLoadsDoNotAccumulateStallTime() {
        val tracker = PlaybackStallTracker()
        tracker.update("a", 0, true, 0)
        assertFalse(tracker.update("a", 0, false, 8000))
        assertFalse(tracker.update("a", 0, false, 60000))
        assertFalse(tracker.update("a", 0, true, 61000))
        assertFalse(tracker.update("a", 0, true, 70999))
        assertTrue(tracker.update("a", 0, true, 71000))
    }
    @Test fun explicitSeekAndRecoveryResetEvenWhenTheTrackAndPositionAreUnchanged() {
        val tracker = PlaybackStallTracker()
        tracker.update("a", 0, true, 0)
        tracker.reset()
        assertFalse(tracker.update("a", 0, true, 10000))
        assertTrue(tracker.update("a", 0, true, 20000))
        tracker.reset()
        assertFalse(tracker.update("a", 0, true, 21000))
    }
}
