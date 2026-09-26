package com.cruisetune.player.playback

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import com.cruisetune.player.dashboard.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class DashboardStartupServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    private val tracks = (0..1).map { Track("startup-$it", "startup", "file-$it", "Pending song $it",
        artist = "Synthetic artist", durationMs = 120_000) }

    @Before fun setup() {
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        app.preferences.edit().putBoolean(DashboardSettings.ENABLED, true).putBoolean("prefetchNextTracks", false).commit()
        app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { index, track -> QueueEntry(track, index) }, 1, 12_345))
    }

    private fun installReceiver() {
        val info = ApplicationInfo().apply {
            packageName = EcarxBroadcastTransport.TARGET_PACKAGE; flags = ApplicationInfo.FLAG_SYSTEM; enabled = true; uid = 1000
        }
        val receiver = ActivityInfo().apply {
            packageName = info.packageName; name = EcarxBroadcastTransport.TARGET_RECEIVER
            applicationInfo = info; exported = true; enabled = true
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = info.packageName; applicationInfo = info; receivers = arrayOf(receiver)
        })
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(EcarxBroadcastTransport.ACTION).setComponent(EcarxBroadcastTransport.component),
            ResolveInfo().apply { activityInfo = receiver },
        )
        assertTrue(EcarxBroadcastTransport(app).inspect().ready)
    }

    private fun broadcasts() = shadowOf(app).broadcastIntents.filter { it.action == EcarxBroadcastTransport.ACTION }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("$message; ${app.dashboardStatus.value}")
    }

    @Test fun restoredIdleTrackIsSentWhenReceiverBecomesAvailableAfterStartup() {
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
            await("Saved queue must restore") { player.mediaItemCount == 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertTrue(broadcasts().isEmpty())
            installReceiver()
            await("Restored song must be sent without starting playback") { broadcasts().isNotEmpty() }
            val sent = broadcasts().single()
            assertEquals("Pending song 1", sent.getStringExtra("RECEIVER_MEDIA_BOOK_NAME"))
            assertEquals("Synthetic artist", sent.getStringExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME"))
            assertEquals(2, sent.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
            assertEquals(12_345L, sent.getLongExtra("RECEIVER_MEDIA_CURRENT_POSITION", -1))
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
            assertEquals(12_345L, player.currentPosition)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals("Idle ticks must not repeatedly publish the song", 1, broadcasts().size)
        } finally { owner.destroy() }
    }

    @Test fun startupWithReadyReceiverPublishesOnceAndStaysIdle() {
        installReceiver()
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
            await("Startup must publish the restored track") { app.dashboardStatus.value.sentCount == 1L }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(1, broadcasts().size)
            assertEquals(DashboardStatusKind.READY, app.dashboardStatus.value.kind)
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
        } finally { owner.destroy() }
    }

    @Test fun startupBeforeDisplayWakesDefersPublicationWithoutResumingPlayback() {
        installReceiver()
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
            await("Restore while screen is off") { player.mediaItemCount == 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertTrue(broadcasts().isEmpty())
            power.setIsInteractive(true)
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            await("Wake must publish the pending song") { app.dashboardStatus.value.sentCount == 1L }
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
            assertEquals(12_345L, player.currentPosition)
        } finally { owner.destroy() }
    }

    @Test fun enablingDashboardAfterRestorePublishesPendingSong() {
        installReceiver()
        app.preferences.edit().putBoolean(DashboardSettings.ENABLED, false).commit()
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
            await("Queue restore") { player.mediaItemCount == 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertTrue(broadcasts().isEmpty())
            app.preferences.edit().putBoolean(DashboardSettings.ENABLED, true).commit()
            await("Enabling sync must publish without playing") { app.dashboardStatus.value.sentCount == 1L }
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
        } finally { owner.destroy() }
    }

    @Test fun selectionChangeCancelsRestoredPublicationWhileReceiverIsUnavailable() {
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
            await("Queue restore") { player.mediaItemCount == 2 }
            player.seekTo(0, 0)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            installReceiver()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertTrue("No stale startup track after manual selection", broadcasts().isEmpty())
        } finally { owner.destroy() }
    }

    @Test fun anotherAudioSourceDefersStartupPublication() {
        installReceiver()
        val audio = app.getSystemService(AudioManager::class.java)
        shadowOf(audio).setIsMusicActive(true)
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
            await("Queue restore") { player.mediaItemCount == 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertTrue(broadcasts().isEmpty())
            shadowOf(audio).setIsMusicActive(false)
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertTrue(broadcasts().isEmpty())
            audio.mode = AudioManager.MODE_NORMAL
            await("Pending song may publish after other audio is released") { app.dashboardStatus.value.sentCount == 1L }
            assertFalse(player.playWhenReady)
            assertEquals(Player.STATE_IDLE, player.playbackState)
        } finally { owner.destroy() }
    }
}
