package com.cruisetune.player

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cruisetune.player.core.*
import com.cruisetune.player.dashboard.*
import com.cruisetune.player.playback.PlaybackService
import com.cruisetune.player.startup.BootReceiver
import com.cruisetune.player.startup.StartupSettings
import com.cruisetune.player.ui.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real service; only vehicle endpoint discovery and transport are substituted. */
@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class DashboardStartupDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as CruiseApplication
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        fail("$message; ${app.dashboardStatus.value}")
    }
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)!!
    private fun session(): MediaSession {
        val sessions = MediaSession::class.java.getDeclaredField("SESSION_ID_TO_SESSION_MAP").apply {
            isAccessible = true
        }.get(null) as Map<*, *>
        return sessions.values.filterIsInstance<MediaSession>().single { it.id.isEmpty() }
    }
    private fun service(session: MediaSession): PlaybackService {
        val impl = MediaSession::class.java.getDeclaredField("impl").apply { isAccessible = true }.get(session)
        val callback = Class.forName("androidx.media3.session.MediaSessionImpl").getDeclaredField("callback").apply {
            isAccessible = true
        }.get(impl)
        return field(checkNotNull(callback), "this\$0") as PlaybackService
    }

    private class TestTransport(private val context: Context) : DashboardTransport {
        @Volatile var ready = false
        val inspections = AtomicInteger()
        val attempts = AtomicInteger()
        val failures = AtomicInteger()
        val dispatched = CopyOnWriteArrayList<Intent>()
        override fun inspect(): DashboardEndpoint {
            inspections.incrementAndGet()
            return DashboardEndpoint(if (ready) DashboardEndpointKind.READY else DashboardEndpointKind.MISSING, "Synthetic endpoint")
        }
        override fun dispatch(intent: Intent): DashboardDispatch {
            attempts.incrementAndGet()
            if (failures.get() > 0) {
                failures.decrementAndGet()
                return DashboardDispatch.Failed("Synthetic temporary failure", true)
            }
            dispatched += Intent(intent)
            // Keep the production payload; route only this test's broadcasts back to its receiver.
            context.sendBroadcast(Intent(intent).setComponent(null).setPackage(context.packageName))
            return DashboardDispatch.Dispatched
        }
    }

    @Test fun bootRestorePublishesPausedSongAndHandlesLateReceiverRetriesAndPlayback() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        val file = File.createTempFile("dashboard-startup-", ".flac", app.cacheDir).apply {
            instrument.context.assets.open("details-fixture.flac").use { input -> outputStream().use { input.copyTo(it) } }
        }
        val source = MusicSource("dashboard-startup", SourceKind.LOCAL, "Synthetic dashboard songs", "test-only")
        val tracks = (0..2).map { Track("dashboard-$it", source.id, "file-$it", "Pending song $it", artist = "Synthetic artist",
            localUri = file.toURI().toString(), mimeType = "audio/flac", size = file.length(), durationMs = 60_000) }
        val keys = listOf(StartupSettings.ENABLED, DashboardSettings.ENABLED, "resumeOnOpen", "prefetchNextTracks")
        val previous = keys.associateWith { key -> if (app.preferences.contains(key)) app.preferences.getBoolean(key, false) else null }
        val transport = TestTransport(app)
        val received = CopyOnWriteArrayList<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received += Intent(intent) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var dashboard: DashboardSyncController? = null
        var activity: MainActivity? = null
        var controller: MediaController? = null
        var playback: PlaybackService? = null
        val monitor = instrument.addMonitor(MainActivity::class.java.name, null, false)
        ContextCompat.registerReceiver(app, receiver, IntentFilter(EcarxBroadcastTransport.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            app.database.saveSource(source); app.database.replaceScan(source.id, tracks)
            app.database.saveQueue(PlaybackSnapshot(1, tracks.mapIndexed { index, track -> QueueEntry(track, index) }, 1, 12_345))
            app.preferences.edit().putBoolean(StartupSettings.ENABLED, true).putBoolean(DashboardSettings.ENABLED, true)
                .putBoolean("resumeOnOpen", false).putBoolean("prefetchNextTracks", false).commit()
            // Simulate delivery of BOOT_COMPLETED without rebooting or modifying the shared device.
            main { BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED)) }
            val notifications = app.getSystemService(NotificationManager::class.java)
            await("Enabled boot receiver must offer the player notification") {
                notifications.activeNotifications.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Cruise Tune 已就绪" }
            }
            main {
                val notice = notifications.activeNotifications.single {
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Cruise Tune 已就绪"
                }
                checkNotNull(notice.notification.contentIntent).send()
            }
            activity = instrument.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
            assertNotNull("Startup notification must open the application", activity)
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val c = future.get(10, TimeUnit.SECONDS); controller = c
            lateinit var engine: Player
            main {
                val session = session()
                engine = session.player
                val service = service(session); playback = service
                (field(service, "dashboard") as DashboardSyncController).close()
                dashboard = DashboardSyncController(scope, DashboardSettings(app.preferences), app.preferences, transport, app.dashboardStatus)
                PlaybackService::class.java.getDeclaredField("dashboard").apply { isAccessible = true }.set(service, dashboard)
            }
            fun command(action: String, args: Bundle = Bundle.EMPTY) {
                lateinit var result: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>
                main { result = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
                assertEquals(0, result.get(10, TimeUnit.SECONDS).resultCode)
            }
            fun screenEvent(action: String) = main {
                // Exercise the service's actual screen callback without changing the host display.
                val screen = field(playback!!, "screenOff")
                (field(screen, "receiver") as BroadcastReceiver).onReceive(app, Intent(action))
            }
            fun assertPending() = main {
                assertEquals(tracks[1].id, engine.currentMediaItem?.mediaId)
                assertEquals(Player.STATE_IDLE, engine.playbackState)
                assertFalse(engine.playWhenReady)
                assertEquals(12_345L, engine.currentPosition)
                assertNull(engine.playerError)
            }
            await("Saved queue must restore") { engine.mediaItemCount == 3 }
            SystemClock.sleep(4_200)
            assertPending()
            assertTrue(received.isEmpty())
            transport.ready = true
            await("Late receiver must receive the restored song without playback") { received.size == 1 && app.dashboardStatus.value.sentCount == 1L }
            val first = received.single()
            assertEquals("Pending song 1", first.getStringExtra("RECEIVER_MEDIA_BOOK_NAME"))
            assertEquals("Synthetic artist", first.getStringExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME"))
            assertEquals(2, first.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
            assertEquals(12_345L, first.getLongExtra("RECEIVER_MEDIA_CURRENT_POSITION", -1))
            assertEquals(EcarxBroadcastTransport.component, transport.dispatched.single().component)
            SystemClock.sleep(4_200)
            assertEquals(1, received.size); assertPending()
            command(PlaybackService.DASHBOARD_RECHECK)
            await("Manual resend must work while paused") { received.size == 2 }

            screenEvent(Intent.ACTION_SCREEN_OFF)
            SystemClock.sleep(2_500)
            assertEquals(2, received.size)
            screenEvent(Intent.ACTION_SCREEN_ON)
            await("Wake must republish pending metadata") { received.size == 3 }
            assertPending()

            main { app.preferences.edit().putBoolean(DashboardSettings.ENABLED, false).apply() }
            await("Sync must be disabled") { app.dashboardStatus.value.kind == DashboardStatusKind.DISABLED }
            SystemClock.sleep(2_500)
            assertEquals(3, received.size)
            main { app.preferences.edit().putBoolean(DashboardSettings.ENABLED, true).apply() }
            await("Re-enabling must republish pending metadata") { received.size == 4 }
            assertPending()

            val beforeRetry = transport.attempts.get()
            transport.failures.set(1)
            command(PlaybackService.DASHBOARD_RECHECK)
            await("Transient failure must retry while idle") { received.size == 5 }
            assertEquals(beforeRetry + 2, transport.attempts.get()); assertPending()

            val beforeCancel = transport.attempts.get()
            transport.failures.set(1)
            command(PlaybackService.DASHBOARD_RECHECK)
            await("Dispatch must fail before cancellation") { transport.attempts.get() == beforeCancel + 1 && app.dashboardStatus.value.kind == DashboardStatusKind.RETRYING }
            screenEvent(Intent.ACTION_SCREEN_OFF)
            SystemClock.sleep(3_000)
            assertEquals(beforeCancel + 1, transport.attempts.get())
            screenEvent(Intent.ACTION_SCREEN_ON)
            await("Wake must start a fresh eligible publication") { received.size == 6 }
            assertPending()

            main { c.seekTo(0, 0) }
            await("Manual selection must change the real engine") { engine.currentMediaItemIndex == 0 }
            SystemClock.sleep(2_500)
            assertEquals("Manual selection must cancel the startup preview", 6, received.size)
            main { c.setAudioAttributes(c.audioAttributes, false); c.prepare(); c.play() }
            await("Actual playback must publish playing state") { engine.isPlaying && received.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) == 3 }
            var position = 0L
            main { position = engine.currentPosition }
            SystemClock.sleep(1_200)
            main { assertTrue(engine.currentPosition > position + 300); assertNull(engine.playerError) }
            main { c.seekToNextMediaItem() }
            await("Next song must publish its own playing metadata") {
                engine.currentMediaItem?.mediaId == tracks[1].id && engine.isPlaying &&
                    received.last().getLongExtra("RECEIVER_MEDIA_BOOK_ID", 0) == DashboardValues.mediaId(app.packageName, tracks[1].id) &&
                    received.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) == 3
            }
            main { c.pause() }
            await("Pause must publish paused state") { !engine.playWhenReady && received.last().getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1) == 2 }
            command(PlaybackService.REMOVE_SOURCE, Bundle().apply { putString("sourceId", source.id) })
            await("Fixture queue must be removed") { engine.mediaItemCount == 0 }
        } finally {
            main { controller?.pause(); dashboard?.close(); scope.cancel(); controller?.release(); activity?.finish() }
            app.stopService(Intent(app, PlaybackService::class.java))
            runBlocking { app.library.removeSources(setOf(source.id), PlaybackSnapshot()) }
            app.preferences.edit().apply { previous.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
            app.unregisterReceiver(receiver)
            instrument.removeMonitor(monitor)
            file.delete()
        }
    }
}
