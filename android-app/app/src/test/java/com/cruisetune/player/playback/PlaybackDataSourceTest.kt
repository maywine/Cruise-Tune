package com.cruisetune.player.playback

import android.app.Application
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@androidx.media3.common.util.UnstableApi
class PlaybackDataSourceTest {
    @Test fun knownBoundaryHandlesExactEndAndDoesNotLimitAnExplicitBypass() {
        var opens = 0; var bypass = false
        val factory = DataSource.Factory { opens++; ByteArrayDataSource(ByteArray(8) { 7 }) }
        val source = PlaybackDataSource(factory, factory, { bypass }, knownLength = { 4 })
        val spec = DataSpec.Builder().setUri("cruisetune://track/a").setKey("a").build()
        try {
            assertEquals(4L, source.open(spec)); val bytes = ByteArray(16)
            assertEquals(4, source.read(bytes, 0, 16)); assertEquals(-1, source.read(bytes, 0, 16))
            source.close()
            assertEquals(0L, source.open(spec.buildUpon().setPosition(4).build()))
            assertEquals(0, source.read(bytes, 0, 0)); assertEquals(-1, source.read(bytes, 0, 16))
            assertEquals(1, opens); assertEquals(spec.uri, source.uri)
            source.close(); bypass = true
            assertEquals(8L, source.open(spec)); assertEquals(8, source.read(bytes, 0, 16))
            assertEquals(2, opens)
        } finally { source.close() }
    }
    @Test fun bypassDoesNotReadOrOverwriteTheOldCacheAndIsScopedToOneKey() {
        var cached = 0; var network = 0
        val bypass = mutableSetOf<String>()
        val source = PlaybackDataSource(DataSource.Factory { cached++; ByteArrayDataSource(byteArrayOf(1, 2, 3, 4)) },
            DataSource.Factory { network++; ByteArrayDataSource(byteArrayOf(5, 6, 7, 8)) }, { it in bypass })
        fun read(key: String): ByteArray {
            val bytes = ByteArray(2)
            try {
                assertEquals(2L, source.open(DataSpec.Builder().setUri("cruisetune://track/$key").setKey(key).setPosition(1).setLength(2).build()))
                assertEquals(2, source.read(bytes, 0, 2))
            } finally { source.close() }
            return bytes
        }
        assertArrayEquals(byteArrayOf(2, 3), read("a"))
        bypass += "a"
        assertArrayEquals(byteArrayOf(6, 7), read("a"))
        assertArrayEquals(byteArrayOf(2, 3), read("b"))
        bypass.clear()
        assertArrayEquals(byteArrayOf(2, 3), read("a"))
        assertEquals(3, cached); assertEquals(1, network)
    }
}
