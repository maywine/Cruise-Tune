package com.cruisetune.player.startup

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cruisetune.player.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
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
    private val notifications get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scheduler get() = app.getSystemService(JobScheduler::class.java)

    @Before fun reset() {
        app.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().clear().commit()
        notifications.cancelAll()
        scheduler.cancelAll()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun disabledStartupDoesNotOpenActivity() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(0, shadowOf(notifications).allNotifications.size)
        assertEquals(0, scheduler.allPendingJobs.size)
    }

    @Test fun enabledStartupShowsTapToOpenNotificationAfterBoot() {
        val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        preferences.edit().putBoolean(StartupSettings.ENABLED, true).commit()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedActivity)
        val openPlayer = shadowOf(notifications).allNotifications.single().contentIntent
        assertNotNull(openPlayer)
        assertEquals(MainActivity::class.java.name, shadowOf(openPlayer).savedIntent.component?.className)
        val job = scheduler.allPendingJobs.single()
        assertEquals(StartupRestoreJobService::class.java.name, job.service.className)
        assertEquals(StartupRestoreJobService.JOB_ID, job.id)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_NONE, job.networkType)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("Cruise Tune 播放器", shadowOf(notifications).allNotifications.single().extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
    }

    @Test fun quickBootUsesTheSameStartupSetting() {
        val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        preferences.edit().putBoolean(StartupSettings.ENABLED, true).commit()
        BootReceiver().onReceive(app, Intent(BootReceiver.QUICKBOOT_POWERON))
        assertEquals(1, shadowOf(notifications).allNotifications.size)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, scheduler.allPendingJobs.size)
    }

    @Test fun unrelatedBroadcastDoesNotOpenActivity() {
        val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        preferences.edit().putBoolean(StartupSettings.ENABLED, true).commit()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(0, shadowOf(notifications).allNotifications.size)
        assertEquals(0, scheduler.allPendingJobs.size)
    }

    @Test @Config(sdk = [33]) fun deniedNotificationPermissionDoesNotBlockBackgroundRestore() {
        app.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putBoolean(StartupSettings.ENABLED, true).commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, scheduler.allPendingJobs.size)
        assertEquals(0, shadowOf(notifications).allNotifications.size)
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
