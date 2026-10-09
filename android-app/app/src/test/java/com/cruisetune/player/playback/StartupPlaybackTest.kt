package com.cruisetune.player.playback

import android.content.ComponentName
import android.content.Intent
import android.media.AudioTrack
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import com.cruisetune.player.ui.MainActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class, shadows = [StartupAudioTrack::class])
@androidx.media3.common.util.UnstableApi
class StartupPlaybackTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    private val power get() = shadowOf(app.getSystemService(PowerManager::class.java))

    @Before fun setup() {
        StartupAudioTrack.advanceWithClock = false
        // The playing screen requests frames continuously; advance virtual time explicitly.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        power.setIsInteractive(true)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        RuntimeEnvironment.setQualifiers("w960dp-h540dp-land-mdpi")
        app.preferences.edit().putBoolean("resumeOnOpen", true).putBoolean("prefetchNextTracks", false).commit()
        val audio = ByteBuffer.allocate(44 + 8000 * 2 * 60).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
        val file = File(app.cacheDir, "startup.wav").apply { writeBytes(audio) }
        val source = MusicSource("startup", SourceKind.LOCAL, "Synthetic", "test")
        val tracks = (0..1).map { Track("startup-$it", source.id, "file-$it", "Song $it",
            localUri = file.toURI().toString(), size = file.length(), mimeType = "audio/wav", durationMs = 60_000) }
        app.database.saveSource(source)
        app.database.replaceScan(source.id, tracks)
        app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { i, track -> QueueEntry(track, i) },
            1, 12_345, playIntent = false, repeatMode = Player.REPEAT_MODE_ALL))
    }

    private fun await(message: String, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("$message ${diagnostic()}")
    }

    private inner class Fixture : AutoCloseable {
        val service: ServiceController<PlaybackService> = Robolectric.buildService(PlaybackService::class.java).create()
        val player: ExoPlayer = ReflectionHelpers.getField(service.get(), "player")
        var activity: ActivityController<MainActivity>? = null
        private val playChanges = mutableListOf<String>()

        init {
            player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    playChanges += "$playWhenReady:$reason"
                }
            })
            // Robolectric needs the real service Binder supplied explicitly for activity binding.
            val binder = service.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        }

        fun open(savedState: Bundle? = null) {
            activity = Robolectric.buildActivity(MainActivity::class.java).create(savedState).start().resume().visible()
            settleStartup()
        }

        fun recreate() {
            val savedState = Bundle()
            // With explicit frame timing, recreate via saved state instead of Robolectric's
            // window-focus shortcut, which expects the new window to have rendered immediately.
            checkNotNull(activity).pause().saveInstanceState(savedState).stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            open(savedState)
        }

        fun settleStartup() {
            val screen = checkNotNull(activity).get()
            await("Activity must connect to the player") { ReflectionHelpers.getField<MediaController?>(screen, "controller") != null }
            val controller = ReflectionHelpers.getField<MediaController>(screen, "controller")
            // Wait for the commands sent on connection, including queue restoration, to finish.
            val barrier = controller.sendCustomCommand(SessionCommand(PlaybackService.DASHBOARD_RECHECK, Bundle.EMPTY), Bundle.EMPTY)
            await("Startup commands must finish") { barrier.isDone }
            assertEquals(SessionResult.RESULT_SUCCESS, barrier.get().resultCode)
        }

        fun assertPlayingAtSavedPosition() {
            await("Restored audio must reach playing", diagnostic = {
                "state=${player.playbackState}, intent=${player.playWhenReady}, suppression=${player.playbackSuppressionReason}, " +
                    "position=${player.currentPosition}, error=${player.playerError}, playChanges=$playChanges"
            }) { player.isPlaying }
            assertEquals("startup-1", player.currentMediaItem?.mediaId)
            assertEquals(12_345L, player.currentPosition)
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            assertEquals(listOf("startup-0", "startup-1"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            assertNull(player.playerError)
        }

        fun pause() {
            player.pause()
            await("Pause must reach the playback thread") { ReflectionHelpers.getField<Int>(player, "pendingOperationAcks") == 0 }
        }

        fun screenOff() {
            power.setIsInteractive(false)
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            await("Screen-off must pause and persist") { !player.playWhenReady && !app.database.restore().playIntent }
        }

        fun screenOn() {
            power.setIsInteractive(true)
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        }

        override fun close() {
            activity?.pause()?.stop()?.destroy()
            shadowOf(Looper.getMainLooper()).idle()
            service.destroy()
        }
    }

    @Test fun startupPlaysSavedPausedQueueWithoutRequiringPreviousPlayIntent() {
        Fixture().use { f -> f.open(); f.assertPlayingAtSavedPosition() }
    }

    @Test fun coldStartupWithRestoredActivityStateStillPlays() {
        Fixture().use { f -> f.open(Bundle().apply { putBoolean("queue", true) }); f.assertPlayingAtSavedPosition() }
    }

    @Test fun disabledAutoplayRestoresWithoutPreparingEvenWhenPreviouslyPlaying() {
        app.preferences.edit().putBoolean("resumeOnOpen", false).commit()
        app.database.savePosition(app.database.restore().copy(playIntent = true))
        Fixture().use { f ->
            f.open()
            assertEquals("startup-1", f.player.currentMediaItem?.mediaId)
            assertEquals(12_345L, f.player.currentPosition)
            assertEquals(Player.STATE_IDLE, f.player.playbackState)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test fun emptyQueueDoesNotStartPlayback() {
        app.database.removeSources(setOf("startup"), PlaybackSnapshot(2))
        Fixture().use { f ->
            f.open()
            assertEquals(0, f.player.mediaItemCount)
            assertEquals(Player.STATE_IDLE, f.player.playbackState)
            assertFalse(f.player.playWhenReady)
        }
    }

    @Test fun backgroundRoundTripsKeepPlaybackAndLaterManualPause() {
        Fixture().use { f ->
            f.open(); f.assertPlayingAtSavedPosition()
            StartupAudioTrack.advanceWithClock = true
            f.activity!!.pause().stop()
            repeat(3) {
                val position = f.player.currentPosition
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                await("Background audio must advance") { f.player.currentPosition > position }
            }
            f.settleStartup()
            assertTrue("Home / another app must not pause playback", f.player.isPlaying)
            f.activity!!.restart().resume()
            f.settleStartup()
            assertTrue("Returning must keep playback running", f.player.isPlaying)
            assertTrue("Background playback must advance", f.player.currentPosition > 12_345L)
            f.pause()
            f.activity!!.pause().stop().restart().resume()
            f.settleStartup()
            assertFalse("Returning after manual pause must stay paused", f.player.playWhenReady)
            f.recreate()
            assertFalse("Recreating the screen must not replay startup", f.player.playWhenReady)
        }
    }

    @Test fun enablingAutoplayDuringUseAppliesToTheNextStartup() {
        app.preferences.edit().putBoolean("resumeOnOpen", false).commit()
        Fixture().use { f ->
            f.open()
            app.preferences.edit().putBoolean("resumeOnOpen", true).commit()
            f.recreate()
            assertFalse(f.player.playWhenReady)
            assertEquals(Player.STATE_IDLE, f.player.playbackState)
        }
        Fixture().use { f -> f.open(); f.assertPlayingAtSavedPosition() }
    }

    @Test fun screenOffPausesWakeStaysPausedAndNextStartupPlays() {
        Fixture().use { f ->
            f.open(); f.assertPlayingAtSavedPosition()
            f.screenOff(); f.screenOn()
            assertFalse("Wake alone must not resume", f.player.playWhenReady)
            f.recreate()
            assertFalse("Screen recreation must not undo screen-off pause", f.player.playWhenReady)
        }
        Fixture().use { f -> f.open(); f.assertPlayingAtSavedPosition() }
    }

    @Test fun startupWhileScreenIsOffDoesNotLeaveAutoplayPendingForWake() {
        power.setIsInteractive(false)
        Fixture().use { f ->
            f.open()
            assertFalse(f.player.playWhenReady)
            f.screenOn()
            f.recreate()
            assertFalse(f.player.playWhenReady)
            assertEquals(Player.STATE_IDLE, f.player.playbackState)
        }
    }
}

// Robolectric drains written PCM immediately by default. Freeze for exact restore assertions,
// and advance with the clock during background playback so Media3 does not detect a false stall.
@Implements(AudioTrack::class)
class StartupAudioTrack : ShadowAudioTrack() {
    companion object { @Volatile var advanceWithClock = false }
    @RealObject private lateinit var track: AudioTrack
    private var lastUpdateMs = SystemClock.elapsedRealtime()
    private var frames = 0L

    @Implementation protected override fun getPlaybackHeadPosition(): Int {
        val now = SystemClock.elapsedRealtime()
        if (advanceWithClock && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            frames = (frames + (now - lastUpdateMs) * track.sampleRate / 1000)
                .coerceAtMost(super.getPlaybackHeadPosition().toLong())
        }
        lastUpdateMs = now
        return frames.toInt()
    }
}
