package com.cruisetune.player.playback

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** A single engine snapshot: controller seek masking can pair a new item with old tags. */
@UnstableApi
internal data class PlaybackMetadata(
    val trackId: String? = null,
    val mediaMetadata: MediaMetadata = MediaMetadata.EMPTY,
    // Null means the audio tags are not loaded; an empty list means no embedded lyrics.
    val lyrics: List<Metadata.Entry>? = null
) {
    fun forTrack(id: String?): PlaybackMetadata? = takeIf { id != null && trackId == id }

    companion object {
        fun from(player: Player): PlaybackMetadata {
            val formats=player.currentTracks.groups.filter { it.type==C.TRACK_TYPE_AUDIO }.flatMap { group ->
                (0 until group.length).filter { group.isTrackSelected(it) }.map(group::getTrackFormat)
            }
            return PlaybackMetadata(player.currentMediaItem?.mediaId,
                if(formats.isEmpty())MediaMetadata.EMPTY else player.mediaMetadata,
                if(formats.isEmpty())null else formats.flatMap { EmbeddedLyrics.entries(it.metadata) })
        }
    }
}
