package com.cruisetune.player.startup

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class StartupSettingsTest {
    @Test fun startupDefaultsOffAndPersists() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("startup-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = StartupSettings(preferences)
        assertFalse(settings.enabled)
        settings.setEnabled(true)
        assertTrue(StartupSettings(preferences).enabled)
        settings.setEnabled(false)
        assertFalse(StartupSettings(preferences).enabled)
    }
}
