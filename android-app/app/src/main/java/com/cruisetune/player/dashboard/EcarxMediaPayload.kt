package com.cruisetune.player.dashboard

import android.content.Intent

internal object EcarxMediaPayload {
    // These are the fields present in the reviewed Ecarx receiver contract. Keep duration and
    // position as Long: the reference client's ReceiverMediaPayload uses Long for both values.
    fun intent(snapshot: DashboardSnapshot): Intent {
        val title = DashboardValues.text(snapshot.title)
        val artist = DashboardValues.text(snapshot.artist)
        val duration = DashboardValues.duration(snapshot.durationMs)
        val position = DashboardValues.position(snapshot.positionMs, duration)
        val mediaId = DashboardValues.mediaId(snapshot.sourcePackage, snapshot.trackId)
        val playing = snapshot.state == DashboardPlaybackState.PLAYING
        val state = if (playing) 3 else 2
        return Intent(EcarxBroadcastTransport.ACTION)
            .setComponent(EcarxBroadcastTransport.component)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .apply {
                putExtra("RECEIVER_MEDIA_COLLECT_STATUS", 0)
                putExtra("RECEIVER_MEDIA_FAVORITE_STATUS", 0)
                putExtra("RECEIVER_MEDIA_PLAY_STATUS", state)
                putExtra("RECEIVER_MEDIA_BOOK_ID", mediaId)
                putExtra("RECEIVER_MEDIA_FRAGMENT_ID", 0L)
                putExtra("RECEIVER_MEDIA_BOOK_NAME", title)
                putExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME", artist)
                putExtra("RECEIVER_MEDIA_BOOK_COVERURL", snapshot.coverUri.orEmpty())
                putExtra("RECEIVER_MEDIA_TOTAL_DURATION", duration)
                putExtra("RECEIVER_MEDIA_CURRENT_POSITION", position)
            }
    }

    /** Includes all fields that can change what the receiver renders. */
    fun signature(snapshot: DashboardSnapshot): String = with(intent(snapshot)) {
        listOf(
            getLongExtra("RECEIVER_MEDIA_BOOK_ID", 0L),
            getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", 0),
            getStringExtra("RECEIVER_MEDIA_BOOK_NAME"),
            getStringExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME"),
            getLongExtra("RECEIVER_MEDIA_TOTAL_DURATION", 0L),
            getLongExtra("RECEIVER_MEDIA_CURRENT_POSITION", 0L),
            getStringExtra("RECEIVER_MEDIA_BOOK_COVERURL"),
        ).joinToString("|")
    }
}
