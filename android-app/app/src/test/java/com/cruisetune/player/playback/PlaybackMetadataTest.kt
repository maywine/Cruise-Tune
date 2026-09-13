package com.cruisetune.player.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.*
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class PlaybackMetadataTest {
    @Test fun tagsCannotBeAssignedToAnOptimisticallySelectedDifferentSong() {
        val snapshot=PlaybackMetadata("first",MediaMetadata.Builder().setArtist("First artist").build(),
            listOf(VorbisComment("LYRICS","[00:00]First line")))
        assertSame(snapshot,snapshot.forTrack("first"))
        assertNull(snapshot.forTrack("second"))
        assertNull(snapshot.forTrack(null))
        assertNull(PlaybackMetadata().forTrack(null))
    }
    @Test fun notLoadedAndConfirmedMissingLyricsHaveDifferentStates() {
        assertNull(PlaybackMetadata("song").lyrics)
        assertEquals(emptyList<Any>(),PlaybackMetadata("song",lyrics=emptyList()).lyrics)
    }
}
