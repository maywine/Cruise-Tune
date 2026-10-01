package com.cruisetune.player.startup

import android.content.SharedPreferences
import com.cruisetune.player.dashboard.DashboardStatus

internal class StartupSettings(private val preferences: SharedPreferences) {
    companion object {
        const val ENABLED = "startup.enabled"
        private const val RECEIVED_AT = "startup.receivedAt"
        private const val RESULT = "startup.result"
        private const val ATTEMPTS = "startup.attempts"
        private const val BOOT_EVENT_AT = "startup.bootEventAt"
        private const val BOOT_ACTION = "startup.bootAction"
        private const val ENABLED_AT_BOOT = "startup.enabledAtBoot"
        private const val ATTEMPT_AT = "startup.attemptAt"
        private const val STAGE = "startup.stage"
        private const val STAGE_AT = "startup.stageAt"
        private const val DASHBOARD_RECORD = "startup.dashboardRecord"
        private const val DASHBOARD_AT = "startup.dashboardAt"
    }

    val enabled: Boolean get() = preferences.getBoolean(ENABLED, false)
    val canRetry: Boolean get() = enabled && preferences.getInt(ATTEMPTS, 0) < 3
    val stage: StartupStage? get() = preferences.getString(STAGE, null)?.let { saved ->
        StartupStage.entries.firstOrNull { it.name == saved }
    }

    fun recordBootEvent(action: String) {
        preferences.edit().putLong(BOOT_EVENT_AT, System.currentTimeMillis())
            .putString(BOOT_ACTION, action).putBoolean(ENABLED_AT_BOOT, enabled).apply()
    }

    fun receivedBoot() {
        val now = System.currentTimeMillis()
        preferences.edit().putLong(RECEIVED_AT, now).putInt(ATTEMPTS, 0)
            .putLong(ATTEMPT_AT, 0).putString(STAGE, StartupStage.QUEUED.name).putLong(STAGE_AT, now)
            .putString(DASHBOARD_RECORD, "").putLong(DASHBOARD_AT, 0)
            .putString(RESULT, "已收到开机事件，等待后台恢复").apply()
    }

    fun beginAttempt() {
        preferences.edit().putInt(ATTEMPTS, preferences.getInt(ATTEMPTS, 0) + 1)
            .putLong(ATTEMPT_AT, System.currentTimeMillis())
            .putString(STAGE, StartupStage.CONNECTING.name).putLong(STAGE_AT, System.currentTimeMillis())
            .putString(RESULT, "正在连接后台播放器").apply()
    }

    fun record(detail: String) { preferences.edit().putString(RESULT, detail).apply() }

    fun recordStage(value: StartupStage) {
        if (stage == value) return
        preferences.edit().putString(STAGE, value.name).putLong(STAGE_AT, System.currentTimeMillis()).apply()
    }

    fun recordDashboard(status: DashboardStatus) {
        val detail = "${status.detail}；记录时本进程已发送 ${status.sentCount} 次（无接收回执）"
        if (preferences.getString(DASHBOARD_RECORD, null) == detail) return
        preferences.edit().putString(DASHBOARD_RECORD, detail).putLong(DASHBOARD_AT, System.currentTimeMillis()).apply()
    }

    fun diagnostic(): String = buildList {
        add("开机恢复开关：${if (enabled) "已开启" else "已关闭"}")
        val eventAt = preferences.getLong(BOOT_EVENT_AT, 0)
        if (eventAt == 0L) add("尚未记录开机广播入口")
        else {
            add("最近开机广播：${formatTime(eventAt)}")
            add("广播类型：${preferences.getString(BOOT_ACTION, "").orEmpty().substringAfterLast('.')}")
            add("收到广播时开关：${if (preferences.getBoolean(ENABLED_AT_BOOT, false)) "已开启" else "已关闭"}")
        }
        val scheduledAt = preferences.getLong(RECEIVED_AT, 0)
        if (scheduledAt == 0L) add("尚未记录后台恢复任务安排")
        else add("最近恢复任务安排：${formatTime(scheduledAt)}")
        val attemptAt = preferences.getLong(ATTEMPT_AT, 0)
        if (attemptAt != 0L) add("最近后台任务：${formatTime(attemptAt)}（第 ${preferences.getInt(ATTEMPTS, 0)}/3 次）")
        stage?.let { add("最近恢复阶段：${it.description}（${formatTime(preferences.getLong(STAGE_AT, 0))}）") }
        preferences.getString(RESULT, null)?.let { add("后台恢复：$it") }
        val dashboardAt = preferences.getLong(DASHBOARD_AT, 0)
        if (dashboardAt != 0L) add("启动窗口仪表记录：${formatTime(dashboardAt)}\n${preferences.getString(DASHBOARD_RECORD, "")}")
    }.joinToString("\n")

    private fun formatTime(time: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(time))

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(ENABLED, value).apply()
    }
}
