package com.cruisetune.player.startup

import android.content.SharedPreferences

internal class StartupSettings(private val preferences: SharedPreferences) {
    companion object {
        const val ENABLED = "startup.enabled"
    }

    val enabled: Boolean get() = preferences.getBoolean(ENABLED, false)

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(ENABLED, value).apply()
    }
}
