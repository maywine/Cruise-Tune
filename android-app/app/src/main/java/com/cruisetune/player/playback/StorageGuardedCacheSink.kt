package com.cruisetune.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec

/** Stop optional cache writes at the storage reserve without stopping the audio upstream. */
@UnstableApi
internal class StorageGuardedCacheSink(private val delegate: DataSink, private val canWrite: (Int) -> Boolean) : DataSink {
    private var writing = false
    override fun open(dataSpec: DataSpec) {
        if (canWrite(1)) { writing = true; delegate.open(dataSpec) }
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (!writing) return
        if (!canWrite(length)) { close(); return }
        delegate.write(buffer, offset, length)
    }
    override fun close() {
        if (writing) { writing = false; delegate.close() }
    }
}
