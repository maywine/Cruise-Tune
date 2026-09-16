package com.cruisetune.player.ui

import android.os.Bundle
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/** Shared appearance and reduced-motion policy for every app-owned screen. */
open class CruiseActivity : AppCompatActivity() {
    private var bottomWindow: BottomClearance.WindowReservation? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("preferences", MODE_PRIVATE)
        Design.configure(prefs.getBoolean("dayMode", false), prefs.getBoolean("highContrast", false), prefs.getBoolean("reduceTransparency", false))
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        Design.applySystemBars(window)
        bottomWindow = BottomClearance.WindowReservation(this)
        if (Design.reducedMotion(this)) {
            window.setWindowAnimations(0)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bottomWindow?.configurationChanged(newConfig)
    }
    override fun onDestroy() {
        bottomWindow?.close()
        super.onDestroy()
    }
    override fun finish() {
        super.finish()
        if (Design.reducedMotion(this)) {
            if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            else { @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
        }
    }
}
