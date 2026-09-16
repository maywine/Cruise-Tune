package com.cruisetune.player.playback

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.cruisetune.player.core.*
import com.cruisetune.player.data.LibraryDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,33],application=Application::class)
@androidx.media3.common.util.UnstableApi
class ScreenOffMonitorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun interactive(value:Boolean) {
        // Robolectric API 23 does not automatically grant the app's merged signature permission.
        shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(value)
    }
    private fun send(action:String) { context.sendBroadcast(Intent(action));shadowOf(Looper.getMainLooper()).idle() }

    @Test fun screenOffClearsPlayRequestAndPreservesPositionAndQueueOnDisk() {
        interactive(true)
        val player=ExoPlayer.Builder(context).build()
        val db=LibraryDatabase(context)
        val track=Track("screen-test","source","file","Song",durationMs=60000)
        val saved=PlaybackSnapshot(1,listOf(QueueEntry(track,0)),0,12345,true)
        db.saveQueue(saved)
        player.setMediaItem(MediaItem.fromUri("https://example.test/audio"));player.seekTo(12345);player.playWhenReady=true
        var prefetchCancelled=false
        val monitor=ScreenOffMonitor(context) {
            player.pause();prefetchCancelled=true
            db.savePosition(saved.copy(positionMs=player.currentPosition,playIntent=false))
        }
        try {
            monitor.start();assertTrue(player.playWhenReady)
            // The request must be cleared even when the player isn't currently outputting audio.
            assertFalse(player.isPlaying)
            send(Intent.ACTION_SCREEN_OFF)
            assertFalse(player.playWhenReady);assertTrue(prefetchCancelled);assertEquals(12345,player.currentPosition)
            db.close()
            val reopened=LibraryDatabase(context)
            try {
                val restored=reopened.restore();assertFalse(restored.playIntent);assertEquals(12345,restored.positionMs);assertEquals("screen-test",restored.entries.single().track.id)
            } finally { reopened.close() }
            send(Intent.ACTION_SCREEN_ON);assertFalse(player.playWhenReady)
        } finally {monitor.stop();player.release();db.close()}
    }
    @Test fun alreadyOffWhenServiceStartsIsDetectedWithoutBroadcast() {
        interactive(false);var pauses=0
        val monitor=ScreenOffMonitor(context){pauses++}
        try {monitor.start();monitor.checkNow();assertEquals(1,pauses);assertTrue(monitor.isScreenOff())}finally{monitor.stop()}
    }
    @Test fun missedBroadcastIsDetectedByInteractiveStateCheck() {
        interactive(true);var pauses=0
        val monitor=ScreenOffMonitor(context){pauses++}
        try {monitor.start();interactive(false);monitor.checkNow();assertEquals(1,pauses);interactive(true);monitor.checkNow();assertEquals(1,pauses)}finally{monitor.stop()}
    }
    @Test fun wakingDoesNotResumeAndNextSleepIsObservedAgain() {
        interactive(true);var pauses=0
        val monitor=ScreenOffMonitor(context){pauses++}
        try {
            monitor.start();send(Intent.ACTION_SCREEN_OFF);send(Intent.ACTION_SCREEN_OFF);assertEquals(1,pauses)
            send(Intent.ACTION_SCREEN_ON);assertFalse(monitor.isScreenOff());assertEquals(1,pauses)
            send(Intent.ACTION_SCREEN_OFF);assertEquals(2,pauses)
        } finally{monitor.stop()}
    }
    @Test fun stopUnregistersAndBackgroundUnrelatedEventsDoNotPause() {
        interactive(true);var pauses=0
        val monitor=ScreenOffMonitor(context){pauses++}
        monitor.start();monitor.start();send(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);assertEquals(0,pauses)
        monitor.stop();monitor.stop();send(Intent.ACTION_SCREEN_OFF);assertEquals(0,pauses)
    }
}
