package com.cruisetune.player.steering

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SteeringSettingsTest {
    @Test fun defaultsAndMappingsSurviveRecreationAndResetDoesNotChangeOptIn() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("steering-test", Context.MODE_PRIVATE)
        val settings = SteeringSettings(prefs)
        assertFalse(settings.enabled)
        assertEquals(SteeringAction.NEXT, settings.action(SteeringKey.RIGHT, SteeringGesture.SINGLE))
        assertEquals(SteeringAction.NONE, settings.action(SteeringKey.RIGHT, SteeringGesture.DOUBLE))
        settings.setAction(SteeringKey.LEFT, SteeringGesture.LONG, SteeringAction.PAUSE)
        prefs.edit().putBoolean(SteeringSettings.ENABLED, true).commit()
        assertEquals(SteeringAction.PAUSE, SteeringSettings(prefs).action(SteeringKey.LEFT, SteeringGesture.LONG))
        settings.resetMappings()
        assertTrue(settings.enabled); assertEquals(SteeringAction.NONE, settings.action(SteeringKey.LEFT, SteeringGesture.LONG))
    }
}
