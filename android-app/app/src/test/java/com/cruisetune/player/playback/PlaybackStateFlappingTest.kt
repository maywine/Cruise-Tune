package com.cruisetune.player.playback

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.FlagSet
import androidx.media3.common.util.StuckPlayerDetector
import androidx.media3.common.util.StuckPlayerException
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class, manifest = Config.NONE)
@androidx.media3.common.util.UnstableApi
class PlaybackStateFlappingTest {
    @Test fun readyBufferingFlappingEvadesTheBuiltinTimersButNotPositionTracking() {
        var state = Player.STATE_BUFFERING
        val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners.remove(args!![0]); null }
                "getPlaybackState" -> state
                "getPlayWhenReady" -> true
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "isPlaying" -> state == Player.STATE_READY
                "getCurrentTimeline" -> Timeline.EMPTY
                "getCurrentAdGroupIndex", "getCurrentAdIndexInAdGroup" -> C.INDEX_UNSET
                "getCurrentPosition", "getBufferedPosition", "getTotalBufferedDuration" -> 0L
                else -> error("Unexpected player method: ${method.name}")
            }
        } as Player
        val builtinFailures = mutableListOf<StuckPlayerException>()
        val builtin = StuckPlayerDetector(player, { builtinFailures += it }, Clock.DEFAULT, 600000, 10000, 60000, 600000)
        val tracker = PlaybackStallTracker()
        var stalls = 0
        try {
            repeat(81) { tick ->
                state = if (tick % 2 == 0) Player.STATE_READY else Player.STATE_BUFFERING
                val events = Player.Events(FlagSet.Builder().add(Player.EVENT_PLAYBACK_STATE_CHANGED).build())
                listeners.toList().forEach { it.onEvents(player, events) }
                val eligible = state == Player.STATE_READY || state == Player.STATE_BUFFERING
                if (tracker.update("cached-track", player.currentPosition, eligible, SystemClock.elapsedRealtime())) stalls++
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            }
            assertTrue("The pinned dependency restarts its timers on each state change", builtinFailures.isEmpty())
            assertEquals("The same stalled episode must report exactly once", 1, stalls)
        } finally { builtin.release() }
    }
}
