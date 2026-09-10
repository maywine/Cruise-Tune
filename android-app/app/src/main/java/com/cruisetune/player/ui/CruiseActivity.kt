package com.cruisetune.player.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Shared appearance and reduced-motion policy for every app-owned screen. */
open class CruiseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("preferences", MODE_PRIVATE)
        Design.configure(prefs.getBoolean("dayMode", false), prefs.getBoolean("highContrast", false), prefs.getBoolean("reduceTransparency", false))
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        Design.applySystemBars(window)
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
    override fun finish() {
        super.finish()
        if (Design.reducedMotion(this)) {
            if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            else { @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
        }
    }
}
