package com.cruisetune.player.steering

import org.junit.Assert.*
import org.junit.Test

class SteeringInputArbiterTest {
    @Test fun registrationWithoutCallbacksNeverConsumesStandardKeys() {
        val routes = SteeringInputArbiter()
        assertFalse(routes.standard(87, 1, 100, 0, 0, 100, true))
        assertFalse(routes.standard(87, 1, 100, 1, 0, 120, true))
        assertFalse(routes.allowsOneOs(SteeringKey.RIGHT, 140))
        assertTrue(routes.allowsOneOs(SteeringKey.RIGHT, 500))
    }
    @Test fun actualOneOsFirstConsumesOnlyTheMatchingStandardPress() {
        val routes = SteeringInputArbiter()
        routes.claimOneOs(SteeringKey.RIGHT, 100)
        assertTrue(routes.standard(87, 1, 110, 0, 0, 110, true))
        assertTrue(routes.standard(87, 1, 110, 1, 0, 900, true)) // Release pairs with its DOWN.
        assertFalse(routes.standard(88, 1, 130, 0, 0, 130, true))
        assertFalse(routes.standard(87, 1, 1000, 0, 0, 1000, true))
    }
    @Test fun diagnosticsAndDisabledModePreserveTheNativePath() {
        val routes = SteeringInputArbiter()
        routes.claimOneOs(SteeringKey.CENTER, 100)
        assertFalse(routes.standard(85, 1, 110, 0, 0, 110, false))
        assertFalse(routes.standard(85, 1, 110, 1, 0, 120, false))
    }
    @Test fun standardLongPressRetainsOwnershipUntilRelease() {
        val routes = SteeringInputArbiter()
        assertFalse(routes.standard(85, 1, 100, 0, 0, 100, true))
        assertFalse(routes.standard(85, 1, 100, 0, 1, 800, true))
        assertFalse(routes.allowsOneOs(SteeringKey.CENTER, 900))
        assertFalse(routes.standard(85, 1, 100, 1, 0, 1100, true))
        assertFalse(routes.allowsOneOs(SteeringKey.CENTER, 1200))
        assertTrue(routes.allowsOneOs(SteeringKey.CENTER, 1600))
    }
    @Test fun clearingPendingOneOsOwnershipDoesNotConsumeAFutureKey() {
        val routes = SteeringInputArbiter()
        routes.claimOneOs(SteeringKey.LEFT, 100); routes.clearOneOs()
        assertFalse(routes.standard(88, 1, 110, 0, 0, 110, true))
        assertFalse(routes.standard(24, 1, 111, 0, 0, 111, true))
    }
}
