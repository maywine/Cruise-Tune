package com.cruisetune.player.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the paused player in a system job and offers a tap-to-open notification. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != QUICKBOOT_POWERON) return
        val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        val settings = StartupSettings(preferences)
        settings.recordBootEvent(action)
        if (!settings.enabled) return
        StartupRestoreJobService.schedule(context)
        StartupNotice.show(context)
    }

    companion object {
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
