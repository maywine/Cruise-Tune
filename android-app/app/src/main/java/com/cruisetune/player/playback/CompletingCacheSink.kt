package com.cruisetune.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Commit a known-length cache range as soon as its last byte has been written.
 * A progressive loader may pause before its next read/close while buffered audio plays.
 */
@UnstableApi
internal class CompletingCacheSink(private val factory: DataSink.Factory) : DataSink {
    private var sink: DataSink? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    override fun open(dataSpec: DataSpec) {
        check(sink == null)
        remaining = dataSpec.length
        val output = factory.createDataSink()
        sink = output
        output.open(dataSpec)
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        if (remaining != C.LENGTH_UNSET.toLong() && length > remaining) throw IOException("Cache range length exceeded")
        val output = sink ?: throw IOException("Cache range is closed")
        output.write(buffer, offset, length)
        if (remaining != C.LENGTH_UNSET.toLong()) {
            remaining -= length
            if (remaining == 0L) close()
        }
    }
    override fun close() {
        val output = sink
        sink = null
        output?.close()
    }
}
