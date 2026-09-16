package com.cruisetune.player.playback

import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.*
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.decoder.flac.FlacExtractor
import androidx.media3.decoder.flac.FlacLibrary
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.*
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers

/** Requires the real host JNI library; the Gradle nativeFlacLibraryDir run never skips these checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CruiseApplication::class, shadows = [CacheSelectionAudioTrack::class])
@androidx.media3.common.util.UnstableApi
class SoftwareFlacPlaybackTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication

    @Before fun nativeDecoderMustLoad() {
        assertTrue("Build decoder-flac/build/host/libflacJNI.so before running native tests", FlacLibrary.isAvailable())
    }

    private class Output(private val retainPcm: Boolean = true) : TrackOutput by DiscardingTrackOutput() {
        var format: Format? = null
        var seekMap: SeekMap? = null
        val samples = mutableListOf<Long>()
        val pcm = ByteArrayOutputStream()
        val digest: MessageDigest = MessageDigest.getInstance("MD5")
        var bytes = 0L
        override fun format(format: Format) { this.format = format }
        override fun sampleData(data: ParsableByteArray, length: Int) {
            digest.update(data.data, data.position, length)
            if (retainPcm) pcm.write(data.data, data.position, length)
            bytes += length
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            samples += timeUs
        }
    }

    private fun extract(bytes: ByteArray, seekUs: Long? = null, retainPcm: Boolean = true): Output {
        val extractor = DefaultExtractorsFactory().createExtractors().filterIsInstance<FlacExtractor>().single()
        val output = Output(retainPcm)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) { output.seekMap = seekMap }
        })
        val source = ByteArrayDataSource(bytes)
        fun open(position: Long): DefaultExtractorInput {
            source.close()
            source.open(DataSpec.Builder().setUri("memory://flac").setPosition(position).build())
            return DefaultExtractorInput(source, position, bytes.size.toLong())
        }
        var input = open(0)
        val position = PositionHolder()
        var sought = false
        try {
            repeat(1000000) {
                val result = extractor.read(input, position)
                if (seekUs != null && !sought && output.seekMap != null) {
                    val point = output.seekMap!!.getSeekPoints(seekUs).first
                    extractor.seek(point.position, seekUs)
                    input = open(point.position)
                    output.pcm.reset(); output.digest.reset(); output.samples.clear(); output.bytes = 0
                    sought = true
                } else if (result == Extractor.RESULT_SEEK) input = open(position.position)
                else if (result == Extractor.RESULT_END_OF_INPUT) return output
            }
            error("Native FLAC extraction failed to reach EOF")
        } finally { extractor.release(); source.close() }
    }

    @Test fun twentyFourBitPcmAndTagsArePreservedWithoutAPlatformFlacDecoder() {
        val fixture = fixture(24, 3747)
        val output = extract(fixture.flac)
        assertEquals(MimeTypes.AUDIO_RAW, output.format!!.sampleMimeType)
        assertEquals(C.ENCODING_PCM_24BIT, output.format!!.pcmEncoding)
        assertEquals(44100, output.format!!.sampleRate)
        assertEquals(2, output.format!!.channelCount)
        assertArrayEquals(fixture.pcm, output.pcm.toByteArray())
        assertEquals(40, output.samples.size)
        assertTrue(output.samples.zipWithNext().all { (a, b) -> b > a })
        val tags = output.format!!.metadata!!
        assertTrue((0 until tags.length()).map { tags[it] }.contains(VorbisComment("ARTIST", "Synthetic Artist")))
    }

    @Test fun sixteenBitFlacRemainsBitExact() {
        val fixture = fixture(16, 4096)
        val output = extract(fixture.flac)
        assertEquals(C.ENCODING_PCM_16BIT, output.format!!.pcmEncoding)
        assertArrayEquals(fixture.pcm, output.pcm.toByteArray())
    }

    @Test fun seekingWithoutASeekTableReturnsTheMatchingPcmAndTimestamp() {
        val fixture = fixture(24, 3747)
        val output = extract(fixture.flac, seekUs = 1_000_000)
        val firstSample = (output.samples.first() * 44100 + 999999) / 1000000
        assertTrue(output.samples.first() in 900000..1_000_000)
        assertArrayEquals(fixture.pcm.copyOfRange(firstSample.toInt() * 6, fixture.pcm.size), output.pcm.toByteArray())
    }

    @Test fun nativeMalformedMetadataIsAFileFailureButNetworkReadFailuresAreNot() {
        val error = runCatching { extract(fixture(24, 3747).flac.copyOf(20)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(PlaybackRecoveryPolicy.isFileFailure(error!!))
        val transport = java.net.SocketTimeoutException("Synthetic timeout")
        val source = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw transport
        }
        val extractor = FlacExtractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        try {
            val failure = runCatching { extractor.read(DefaultExtractorInput(source, 0, 100), PositionHolder()) }.exceptionOrNull()
            assertSame(transport, failure)
            assertFalse(PlaybackRecoveryPolicy.isFileFailure(failure!!))
        } finally { extractor.release() }
    }

    @Test fun completeCachedTwentyFourBitFlacRendersPcmWithoutNetworkOrMediaCodecDecoding() {
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ShadowStatFs.registerStats(app.filesDir.absolutePath, 2000000, 1500000, 1500000)
        app.preferences.edit().putBoolean("prefetchNextTracks", false).commit()
        val fixture = fixture(24, 3747)
        val source = MusicSource("native-flac", SourceKind.QUARK, "Synthetic", "root")
        val track = Track("native-flac", source.id, "file", "Synthetic", size = fixture.flac.size.toLong(), mimeType = MimeTypes.AUDIO_FLAC)
        app.database.saveSource(source); app.database.replaceScan(source.id, listOf(track))
        app.database.saveQueue(PlaybackSnapshot(1, listOf(QueueEntry(track, 0))))
        val writer = CacheDataSource.Factory().setCache(app.media.stream)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(fixture.flac) }.createDataSource()
        CacheWriter(writer, DataSpec.Builder().setUri("cruisetune://track/${track.id}").setKey(track.cacheKey).build(), null, null).cache()
        var networkReads = 0
        app.media.streamFactory.setUpstreamDataSourceFactory {
            object : DataSource by ByteArrayDataSource(byteArrayOf(0)) {
                override fun open(dataSpec: DataSpec): Long {
                    networkReads++
                    throw IOException("Complete cache must not use the network")
                }
            }
        }
        val owner = Robolectric.buildService(PlaybackService::class.java).create()
        val player = ReflectionHelpers.getField<ExoPlayer>(owner.get(), "player")
        try {
            await { player.mediaItemCount == 1 }
            player.setAudioAttributes(player.audioAttributes, false)
            player.prepare(); player.play()
            await(diagnostic = { "state=${player.playbackState}, format=${player.audioFormat}, error=${player.playerError?.stackTraceToString()}, rendered=${player.audioDecoderCounters?.renderedOutputBufferCount}" }) {
                player.playbackState == Player.STATE_READY && (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            assertNull(player.playerError)
            assertEquals(MimeTypes.AUDIO_RAW, player.audioFormat!!.sampleMimeType)
            assertEquals(0, player.audioDecoderCounters!!.decoderInitCount)
            assertEquals(0, networkReads)
            assertTrue(app.media.hasCompleteStreamingCache(track))
        } finally { owner.destroy() }
    }

    @Test fun suppliedLocalSamplesMatchTheirEmbeddedPcmChecksums() {
        val directory = System.getProperty("flacSamplesDir")
        val samples = if (directory == null) sequenceOf(fixture(24, 3747).flac, fixture(16, 4096).flac)
            else File(directory).listFiles()!!.filter { it.extension.equals("flac", true) }.sortedBy { it.name }.asSequence().map { it.readBytes() }
        var count = 0
        samples.forEachIndexed { index, bytes ->
            count++
            assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
            val info = FlacStreamMetadata(bytes, 8)
            val output = extract(bytes, retainPcm = false)
            assertEquals("Sample $index decoded length", info.totalSamples * info.channels * (info.bitsPerSample / 8), output.bytes)
            assertArrayEquals("Sample $index PCM MD5", bytes.copyOfRange(26, 42), output.digest.digest())
        }
        assertTrue("Sample directory must contain FLAC files", count > 0)
        println("Verified $count complete FLAC files through the bundled native decoder")
    }

    private fun await(diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Expected software-decoded playback condition: ${diagnostic()}")
    }

    private data class Fixture(val flac: ByteArray, val pcm: ByteArray)
    private fun fixture(bits: Int, block: Int): Fixture {
        val frames = 40
        val pcm = ByteArrayOutputStream()
        val audio = ByteArrayOutputStream()
        fun sample(index: Int, channel: Int): Int = (index * 973 + channel * 123) % (1 shl bits) - (1 shl (bits - 1))
        for (index in 0 until block * frames) for (channel in 0..1) {
            val value = sample(index, channel)
            repeat(bits / 8) { pcm.write(value ushr (8 * it)) }
        }
        repeat(frames) { frame ->
            val buffer = ByteArrayOutputStream()
            val out = DataOutputStream(buffer)
            out.writeShort(0xfff8); out.writeByte(0x79)
            out.writeByte(0x10 or ((if (bits == 24) 6 else 4) shl 1))
            out.writeByte(frame); out.writeShort(block - 1)
            out.writeByte(crc(buffer.toByteArray(), 8, 0x07))
            for (channel in 0..1) {
                out.writeByte(2) // Verbatim subframe, to exercise every PCM bit without an external encoder.
                repeat(block) { offset ->
                    val value = sample(frame * block + offset, channel)
                    for (byte in bits / 8 - 1 downTo 0) out.writeByte(value ushr (8 * byte))
                }
            }
            out.writeShort(crc(buffer.toByteArray(), 16, 0x8005))
            audio.write(buffer.toByteArray())
        }
        val info = ByteBuffer.allocate(34).putShort(block.toShort()).putShort(block.toShort())
        repeat(6) { info.put(0) } // Unknown frame byte sizes are valid and require no guessed bounds.
        info.putLong((44100L shl 44) or (1L shl 41) or ((bits - 1L) shl 36) or (block * frames).toLong())
        info.put(MessageDigest.getInstance("MD5").digest(pcm.toByteArray()))
        val comment = "ARTIST=Synthetic Artist".toByteArray()
        val tags = ByteBuffer.allocate(12 + comment.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(1).putInt(comment.size).put(comment).array()
        val result = ByteArrayOutputStream()
        val out = DataOutputStream(result)
        out.writeBytes("fLaC"); out.writeInt(34); out.write(info.array())
        out.writeInt((0x84 shl 24) or tags.size); out.write(tags); out.write(audio.toByteArray())
        return Fixture(result.toByteArray(), pcm.toByteArray())
    }

    private fun crc(bytes: ByteArray, bits: Int, polynomial: Int): Int {
        var value = 0
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 255) shl (bits - 8))
            repeat(8) { value = if (value and (1 shl (bits - 1)) != 0) (value shl 1) xor polynomial else value shl 1 }
            value = value and ((1 shl bits) - 1)
        }
        return value
    }
}
