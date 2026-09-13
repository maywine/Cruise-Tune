package com.cruisetune.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** The route is selected on each open, including seeks; an active read keeps its original source. */
@UnstableApi
internal class PlaybackDataSource(
    private val cached: DataSource.Factory,
    private val uncached: DataSource.Factory,
    private val bypass: (String?) -> Boolean,
    private val beginCachedRead: (String?) -> Unit = {},
) : DataSource {
    private var source: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun open(dataSpec: DataSpec): Long {
        check(source == null)
        val bypassCache = bypass(dataSpec.key)
        if (!bypassCache) beginCachedRead(dataSpec.key)
        val selected = (if (bypassCache) uncached else cached).createDataSource()
        source = selected
        listeners.forEach(selected::addTransferListener)
        return selected.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(source).read(buffer, offset, length)
    override fun getUri() = source?.uri
    override fun getResponseHeaders() = source?.responseHeaders ?: emptyMap()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; source?.addTransferListener(listener) }
    override fun close() { val old = source; source = null; old?.close() }
}
