package com.cruisetune.player.dashboard

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class EcarxMediaPayloadTest {
    private fun snapshot(state: DashboardPlaybackState = DashboardPlaybackState.PLAYING) = DashboardSnapshot(
        trackId = "cloud-track-42", sourcePackage = "com.cruisetune.player", title = "日落\u0000 - 孙燕姿",
        artist = "孙燕姿", album = "克卜勒", state = state, durationMs = Long.MAX_VALUE,
        positionMs = Long.MAX_VALUE,
    )

    @Test fun payloadTargetsOnlyTheReviewedReceiverWithTypedCompatibleExtras() {
        val intent = EcarxMediaPayload.intent(snapshot())
        assertEquals(EcarxBroadcastTransport.ACTION, intent.action)
        assertEquals(EcarxBroadcastTransport.component, intent.component)
        assertTrue(intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES != 0)
        assertEquals("日落 - 孙燕姿", intent.getStringExtra("RECEIVER_MEDIA_BOOK_NAME"))
        assertEquals(3, intent.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
        assertEquals(Int.MAX_VALUE, intent.getIntExtra("RECEIVER_MEDIA_TOTAL_DURATION", -1))
        assertEquals(Int.MAX_VALUE, intent.getIntExtra("RECEIVER_MEDIA_CURRENT_POSITION", -1))
        assertTrue(intent.extras!!.get("RECEIVER_MEDIA_BOOK_ID") is Long)
        assertTrue(intent.extras!!.get("RECEIVER_MEDIA_TOTAL_DURATION") is Int)
        assertTrue(intent.extras!!.get("RECEIVER_MEDIA_CURRENT_POSITION") is Int)
        assertEquals("", intent.getStringExtra("RECEIVER_MEDIA_BOOK_COVERURL"))
        assertEquals(intent.getStringExtra("mediaId"), intent.getStringExtra("trackId"))
        assertFalse(intent.getStringExtra("trackId")!!.contains("cloud-track-42"))
    }

    @Test fun pausedPayloadKeepsMetadataButUsesTheConfirmedPausedValue() {
        val intent = EcarxMediaPayload.intent(snapshot(DashboardPlaybackState.PAUSED))
        assertEquals(2, intent.getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", -1))
        assertFalse(intent.getBooleanExtra("isPlaying", true))
        assertEquals("paused", intent.getStringExtra("playbackState"))
    }
}
