package com.cruisetune.player.startup

import android.content.SharedPreferences

internal class StartupSettings(private val preferences: SharedPreferences) {
    companion object {
        const val ENABLED = "startup.enabled"
        private const val RECEIVED_AT = "startup.receivedAt"
        private const val RESULT = "startup.result"
        private const val ATTEMPTS = "startup.attempts"
    }

    val enabled: Boolean get() = preferences.getBoolean(ENABLED, false)
    val canRetry: Boolean get() = enabled && preferences.getInt(ATTEMPTS, 0) < 3

    fun receivedBoot() {
        preferences.edit().putLong(RECEIVED_AT, System.currentTimeMillis()).putInt(ATTEMPTS, 0)
            .putString(RESULT, "已收到开机事件，等待后台恢复").apply()
    }

    fun beginAttempt() {
        preferences.edit().putInt(ATTEMPTS, preferences.getInt(ATTEMPTS, 0) + 1)
            .putString(RESULT, "正在后台恢复上次队列").apply()
    }

    fun record(detail: String) { preferences.edit().putString(RESULT, detail).apply() }

    fun diagnostic(): String {
        val received = preferences.getLong(RECEIVED_AT, 0)
        if (received == 0L) return "尚未记录开机恢复事件"
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(received))
        val result = preferences.getString(RESULT, "等待恢复")
        return "最近开机事件：$time\n后台恢复：$result"
    }

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(ENABLED, value).apply()
    }
}
