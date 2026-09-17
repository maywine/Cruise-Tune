package com.cruisetune.player.dashboard

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class DashboardSettingsTest {
    @Test fun optInDefaultsOffAndSurvivesRecreation() {
        val preferences = (RuntimeEnvironment.getApplication() as Application).getSharedPreferences("dashboard-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        assertFalse(DashboardSettings(preferences).enabled)
        DashboardSettings(preferences).setEnabled(true)
        assertTrue(DashboardSettings(preferences).enabled)
    }

    @Test fun diagnosticsNeverIncludeMusicData() {
        val report = DashboardStatus(DashboardStatusKind.READY, "已发送，需在仪表媒体菜单确认", sentCount = 3).diagnosticReport("0.5.31", 28)
        assertTrue(report.contains("本机已发送：3 次"))
        assertFalse(report.contains("孙燕姿"))
    }
}
