package com.cruisetune.player.playback

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class, manifest = Config.NONE)
class AudioTrackShadowCompatibilityTest {
    @Test fun pcmPositionWorksBeforeTheApi29FrameSizeMethodExists() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(4096).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            assertEquals(160, track.write(ByteArray(160), 0, 160))
            // Old shadows called the API 29-only frame-size method here and killed the playback thread.
            assertEquals(80, track.playbackHeadPosition)
        } finally { track.release() }
    }
}
