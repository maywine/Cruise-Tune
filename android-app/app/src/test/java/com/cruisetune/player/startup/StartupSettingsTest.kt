package com.cruisetune.player.startup

import android.app.Application
import android.content.Context
import android.content.Intent
import com.cruisetune.player.dashboard.DashboardStatus
import com.cruisetune.player.dashboard.DashboardStatusKind
import org.junit.Assert.assertEquals
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
    private fun settings(): StartupSettings {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("startup-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return StartupSettings(preferences)
    }

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

    @Test fun diagnosticShowsCurrentSettingWithoutInferringBootDelivery() {
        val settings = settings()
        assertTrue(settings.diagnostic().contains("开机恢复开关：已关闭"))
        settings.setEnabled(true)
        val report = settings.diagnostic()
        assertTrue(report.contains("开机恢复开关：已开启"))
        assertTrue(report.contains("尚未记录开机广播入口"))
        assertTrue(report.contains("尚未记录后台恢复任务安排"))
    }

    @Test fun bootEventKeepsTheSettingAtReceiptAfterItChanges() {
        val settings = settings()
        settings.recordBootEvent(Intent.ACTION_BOOT_COMPLETED)
        settings.setEnabled(true)
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("startup-test", Context.MODE_PRIVATE)
        val report = StartupSettings(preferences).diagnostic()
        assertTrue(report.contains("开机恢复开关：已开启"))
        assertTrue(report.contains("收到广播时开关：已关闭"))
        assertTrue(report.contains("最近开机广播："))
        assertTrue(report.contains("广播类型：BOOT_COMPLETED"))
        assertTrue(report.contains("尚未记录后台恢复任务安排"))
    }

    @Test fun failureBeforeSchedulingIsNotHiddenByMissingTimestamp() {
        val settings = settings()
        settings.setEnabled(true)
        settings.recordBootEvent(Intent.ACTION_BOOT_COMPLETED)
        settings.record("后台恢复任务未能安排，请点通知打开播放器")
        val report = settings.diagnostic()
        assertTrue(report.contains("收到广播时开关：已开启"))
        assertTrue(report.contains("尚未记录后台恢复任务安排"))
        assertTrue(report.contains("后台恢复：后台恢复任务未能安排"))
    }

    @Test fun legacySchedulingRecordDoesNotInventABroadcastReceipt() {
        val settings = settings()
        settings.receivedBoot()
        settings.record("已恢复上次队列，保持暂停")
        val report = settings.diagnostic()
        assertTrue(report.contains("尚未记录开机广播入口"))
        assertTrue(report.contains("最近恢复任务安排："))
        assertTrue(report.contains("后台恢复：已恢复上次队列，保持暂停"))
    }

    @Test fun stageAndDashboardEvidenceSurviveReopeningButResetForANewBootTask() {
        val settings = settings()
        settings.receivedBoot()
        settings.beginAttempt()
        settings.recordStage(StartupStage.SYNCING)
        settings.record("已恢复上次队列，保持暂停")
        settings.recordDashboard(DashboardStatus(DashboardStatusKind.READY, "已发送，需在仪表媒体菜单确认", sentCount = 4))
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("startup-test", Context.MODE_PRIVATE)
        val reopened = StartupSettings(preferences)
        assertEquals(StartupStage.SYNCING, reopened.stage)
        val report = reopened.diagnostic()
        assertTrue(report.contains("最近后台任务"))
        assertTrue(report.contains("仪表启动同步窗口"))
        assertTrue(report.contains("记录时本进程已发送 4 次（无接收回执）"))
        reopened.receivedBoot()
        assertEquals(StartupStage.QUEUED, reopened.stage)
        assertFalse(reopened.diagnostic().contains("最近后台任务"))
        assertFalse(reopened.diagnostic().contains("启动窗口仪表记录"))
    }
}
