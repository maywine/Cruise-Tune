package com.cruisetune.player.dashboard

import android.content.Intent

internal object EcarxMediaPayload {
    fun intent(snapshot: DashboardSnapshot): Intent {
        val title = DashboardValues.text(snapshot.title)
        val artist = DashboardValues.text(snapshot.artist)
        val album = DashboardValues.text(snapshot.album)
        val duration = DashboardValues.duration(snapshot.durationMs)
        val position = DashboardValues.position(snapshot.positionMs, duration)
        val mediaId = DashboardValues.mediaId(snapshot.sourcePackage, snapshot.trackId)
        val playing = snapshot.state == DashboardPlaybackState.PLAYING
        val state = if (playing) 3 else 2
        val stateText = if (playing) "playing" else "paused"
        val id = mediaId.toString()
        return Intent(EcarxBroadcastTransport.ACTION)
            .setComponent(EcarxBroadcastTransport.component)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .apply {
                putExtra("RECEIVER_MEDIA_COLLECT_STATUS", 0)
                putExtra("RECEIVER_MEDIA_PLAY_STATUS", state)
                putExtra("mediaState", state)
                putExtra("RECEIVER_MEDIA_BOOK_ID", mediaId)
                putExtra("RECEIVER_MEDIA_FRAGMENT_ID", 0L)
                putExtra("RECEIVER_MEDIA_BOOK_NAME", title)
                putExtra("RECEIVER_MEDIA_TITLE", title)
                putExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME", artist)
                putExtra("RECEIVER_MEDIA_ARTIST", artist)
                putExtra("RECEIVER_MEDIA_ALBUM", album)
                putExtra("RECEIVER_MEDIA_BOOK_COVERURL", snapshot.coverUri.orEmpty())
                putExtra("RECEIVER_MEDIA_COVER_URL", snapshot.coverUri.orEmpty())
                putExtra("RECEIVER_MEDIA_TOTAL_DURATION", duration)
                putExtra("RECEIVER_MEDIA_DURATION", duration)
                putExtra("duration", duration)
                putExtra("RECEIVER_MEDIA_CURRENT_POSITION", position)
                putExtra("RECEIVER_MEDIA_POSITION", position)
                putExtra("position", position)
                putExtra("RECEIVER_MEDIA_PACKAGE_NAME", snapshot.sourcePackage)
                putExtra("mediaId", id)
                putExtra("songId", id)
                putExtra("trackId", id)
                putExtra("uuid", id)
                putExtra("xm_track_id", id)
                putExtra("xm_album_id", id)
                putExtra("isPlaying", playing)
                putExtra("playbackState", stateText)
            }
    }

    /** Includes all fields that can change what the receiver renders. */
    fun signature(snapshot: DashboardSnapshot): String = with(intent(snapshot)) {
        listOf(
            getLongExtra("RECEIVER_MEDIA_BOOK_ID", 0L),
            getIntExtra("RECEIVER_MEDIA_PLAY_STATUS", 0),
            getStringExtra("RECEIVER_MEDIA_BOOK_NAME"),
            getStringExtra("RECEIVER_MEDIA_BOOK_AUTHOR_NAME"),
            getStringExtra("RECEIVER_MEDIA_ALBUM"),
            getIntExtra("RECEIVER_MEDIA_TOTAL_DURATION", 0),
            getIntExtra("RECEIVER_MEDIA_CURRENT_POSITION", 0),
            getStringExtra("RECEIVER_MEDIA_BOOK_COVERURL"),
        ).joinToString("|")
    }
}
