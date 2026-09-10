package com.cruisetune.player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.core.content.ContextCompat

/** Observe system sleep and the main display; moving the activity to background is not screen-off. */
internal class ScreenOffMonitor(private val context: Context, private val onScreenOff: () -> Unit) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var registered = false
    private var broadcastOff = false
    private var observedOff = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { broadcastOff = true; checkNow() }
                Intent.ACTION_SCREEN_ON -> { broadcastOff = false; checkNow() }
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) checkNow() }
        override fun onDisplayRemoved(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) checkNow() }
        override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) checkNow() }
    }
    fun isScreenOff(): Boolean {
        val state = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.state
        return broadcastOff || power?.isInteractive == false || state == Display.STATE_OFF ||
            state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND
    }
    fun start() {
        if (registered) return
        broadcastOff = false; observedOff = false
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        displays?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        checkNow()
    }
    fun checkNow() {
        if (!registered) return
        val off = isScreenOff()
        if (off && !observedOff) { observedOff = true; onScreenOff() }
        else if (!off) observedOff = false
        // Waking only resets observation; it never resumes playback.
    }
    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
        displays?.unregisterDisplayListener(displayListener)
    }
}
