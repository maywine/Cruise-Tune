package com.cruisetune.player.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cruisetune.player.ui.MainActivity

/** Opens the player after boot only when the user has explicitly enabled the option. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        if (!StartupSettings(preferences).enabled) return
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }
}
