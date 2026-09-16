package com.cruisetune.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** The route is selected on each open, including seeks; an active read keeps its original source. */
@UnstableApi
internal class PlaybackDataSource(
    private val cached: DataSource.Factory,
    private val uncached: DataSource.Factory,
    private val bypass: (String?) -> Boolean,
    private val knownLength: (DataSpec) -> Long = { C.LENGTH_UNSET.toLong() },
    private val beginCachedRead: (String?) -> Unit = {},
) : DataSource {
    private var source: DataSource? = null
    private var openedSpec: DataSpec? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private val listeners = mutableListOf<TransferListener>()
    override fun open(dataSpec: DataSpec): Long {
        check(openedSpec == null)
        openedSpec = dataSpec
        val bypassCache = bypass(dataSpec.key)
        val total = if (bypassCache) C.LENGTH_UNSET.toLong() else knownLength(dataSpec)
        val bounded = if (total >= 0) {
            if (dataSpec.position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            remaining = (total - dataSpec.position).let { if (dataSpec.length >= 0) minOf(it, dataSpec.length) else it }
            if (remaining == 0L) return 0
            dataSpec.buildUpon().setLength(remaining).build()
        } else dataSpec
        if (!bypassCache) beginCachedRead(dataSpec.key)
        val selected = (if (bypassCache) uncached else cached).createDataSource()
        source = selected
        listeners.forEach(selected::addTransferListener)
        val length = selected.open(bounded)
        return if (remaining >= 0) remaining else length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkNotNull(openedSpec)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = checkNotNull(source).read(buffer, offset, if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length)
        if (remaining > 0) {
            if (count == C.RESULT_END_OF_INPUT) throw java.io.EOFException("Cache ended before the known file boundary")
            remaining -= count
        }
        return count
    }
    override fun getUri() = source?.uri ?: openedSpec?.uri
    override fun getResponseHeaders() = source?.responseHeaders ?: emptyMap()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; source?.addTransferListener(listener) }
    override fun close() { val old = source; source = null; openedSpec = null; remaining = C.LENGTH_UNSET.toLong(); old?.close() }
}
