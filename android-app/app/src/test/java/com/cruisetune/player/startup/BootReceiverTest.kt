package com.cruisetune.player.startup

import android.app.Application
import android.content.Context
import android.content.Intent
import com.cruisetune.player.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class BootReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication() as Application

    @Test fun disabledStartupDoesNotOpenActivity() {
        val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun enabledStartupOpensMainActivityAfterBoot() {
        val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean(StartupSettings.ENABLED, true).commit()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val launched = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, launched.component?.className)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test fun unrelatedBroadcastDoesNotOpenActivity() {
        val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean(StartupSettings.ENABLED, true).commit()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
