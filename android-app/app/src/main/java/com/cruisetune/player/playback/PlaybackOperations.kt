package com.cruisetune.player.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cruisetune.player.core.QueueEntry

@UnstableApi
internal object PlaybackOperations {
    fun retry(player: Player) {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)
        // prepare() is a no-op in READY/BUFFERING. Stop the old load before preparing it again.
        player.stop()
        player.seekTo(index, position)
        player.prepare()
        player.play()
    }

    fun reorder(player: Player, ordered: List<QueueEntry>) {
        // Moving existing items preserves the active decoder, buffer and position.
        ordered.forEachIndexed { target, entry ->
            val from = (target until player.mediaItemCount).first { player.getMediaItemAt(it).mediaId == entry.track.id }
            if (from != target) player.moveMediaItem(from, target)
        }
    }
}
