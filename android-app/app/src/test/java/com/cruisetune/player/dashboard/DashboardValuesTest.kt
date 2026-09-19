package com.cruisetune.player.dashboard

import org.junit.Assert.*
import org.junit.Test

class DashboardValuesTest {
    @Test fun mediaIdIsStablePositiveAndDoesNotDependOnDisplayedMetadata() {
        val first = DashboardValues.mediaId("com.cruisetune.player", "track-1")
        assertEquals(first, DashboardValues.mediaId("com.cruisetune.player", "track-1"))
        assertNotEquals(first, DashboardValues.mediaId("com.cruisetune.player", "track-2"))
        assertTrue(first > 0)
    }

    @Test fun textAndTimeFieldsAreSafeForTheReceiverContract() {
        assertEquals("A B", DashboardValues.text("A\u0000 B"))
        assertEquals(0L, DashboardValues.duration(null))
        assertEquals(0L, DashboardValues.duration(-1))
        assertEquals(Long.MAX_VALUE, DashboardValues.duration(Long.MAX_VALUE))
        assertEquals(0L, DashboardValues.position(-4, 1000))
        assertEquals(1000L, DashboardValues.position(5000, 1000))
        assertEquals(Long.MAX_VALUE, DashboardValues.position(Long.MAX_VALUE, 0))
        assertEquals(256, DashboardValues.text("🎵".repeat(300)).codePointCount(0, DashboardValues.text("🎵".repeat(300)).length))
    }
}
