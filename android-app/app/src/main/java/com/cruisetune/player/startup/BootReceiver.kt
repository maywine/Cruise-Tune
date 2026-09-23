package com.cruisetune.player.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Leaves a user-initiated way into the restored player after boot. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != QUICKBOOT_POWERON) return
        val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        if (!StartupSettings(preferences).enabled) return
        StartupNotice.show(context)
    }

    companion object {
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
