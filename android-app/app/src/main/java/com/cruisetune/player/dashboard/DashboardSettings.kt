package com.cruisetune.player.dashboard

import android.content.SharedPreferences

internal class DashboardSettings(private val preferences: SharedPreferences) {
    companion object { const val ENABLED = "dashboard.sync.enabled" }

    val enabled: Boolean get() = preferences.getBoolean(ENABLED, false)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(ENABLED, value).apply()
    }
}
internal enum class DashboardStatusKind {
    DISABLED, WAITING, CHECKING, READY, UNAVAILABLE, RETRYING
}

internal data class DashboardStatus(
    val kind: DashboardStatusKind = DashboardStatusKind.DISABLED,
    val detail: String = "未开启仪表媒体显示",
    val sentCount: Long = 0,
    val lastSentElapsedMs: Long? = null,
    val receiverVersion: String? = null,
    val receiverUid: Int? = null,
    val receiverSystemApp: Boolean? = null,
    val lastFailure: String? = null,
) {
    val enabled: Boolean get() = kind != DashboardStatusKind.DISABLED

    fun diagnosticReport(version: String, sdk: Int): String = buildString {
        appendLine("Cruise Tune $version · Android API $sdk")
        appendLine("仪表媒体同步：$detail")
        appendLine("本机已发送：$sentCount 次（普通广播无接收回执）")
        receiverVersion?.let { appendLine("目标服务版本：$it") }
        receiverUid?.let { appendLine("目标服务 UID：$it") }
        receiverSystemApp?.let { appendLine("目标服务系统应用：$it") }
        lastFailure?.let { appendLine("最近失败：$it") }
        append("不含歌名、账号、文件路径或网盘凭证")
    }
}
