package com.cruisetune.player.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import com.cruisetune.player.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReadPathTest {
    private val track = Track("song", "source", "file", "Song", size = 200000, version = "first")
    @Test fun expiredLinkRetriesSameByteRangeWithFreshAuthorization() {
        var resolves = 0
        val opened = mutableListOf<DataSpec>()
        val source = ResolvingTrackSource({ track }, { ReadRequest("https://pdds.quark.cn/file?token=${++resolves}", mapOf("Cookie" to "session-$resolves")) }, DataSource.Factory {
            object : EmptySource() {
                override fun open(dataSpec: DataSpec): Long {
                    opened += dataSpec
                    if (opened.size == 1) throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, byteArrayOf())
                    return dataSpec.length
                }
            }
        })
        val spec = DataSpec.Builder().setUri("cruisetune://track/song").setKey(track.cacheKey).setPosition(81000).setLength(4096).build()
        assertEquals(4096, source.open(spec))
        assertEquals(2, resolves)
        opened.forEach { assertEquals(81000, it.position); assertEquals(4096, it.length); assertEquals(track.cacheKey, it.key) }
        assertNotEquals(opened[0].uri, opened[1].uri)
        assertEquals("session-2", opened[1].httpRequestHeaders["Cookie"])
        source.close()
    }
    @Test fun changedFileVersionCannotFillOldCacheWithNewContent() {
        var resolved = false
        val source = ResolvingTrackSource({ track.copy(version = "changed") }, { resolved = true; ReadRequest("https://pdds.quark.cn/file", emptyMap()) }, DataSource.Factory { EmptySource() })
        val spec = DataSpec.Builder().setUri("cruisetune://track/song").setKey(track.cacheKey).build()
        assertTrue(runCatching { source.open(spec) }.exceptionOrNull() is UserError)
        assertFalse(resolved)
    }
    @Test fun downloadUrlNotFoundRefreshesTheUrlOnceWithoutChangingTheRange() {
        var resolves = 0
        var authRefreshes = 0
        val opened = mutableListOf<DataSpec>()
        val source = ResolvingTrackSource({ track }, { ReadRequest("https://pdds.quark.cn/file?token=${++resolves}", emptyMap()) }, DataSource.Factory {
            object : EmptySource() {
                override fun open(dataSpec: DataSpec): Long {
                    opened += dataSpec
                    if (opened.size == 1) throw HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, emptyMap(), dataSpec, byteArrayOf())
                    return dataSpec.length
                }
            }
        }, { _, _ -> authRefreshes++ })
        val spec = DataSpec.Builder().setUri("cruisetune://track/song").setKey(track.cacheKey).setPosition(81000).setLength(4096).build()
        assertEquals(4096, source.open(spec))
        assertEquals(2, resolves)
        assertEquals(0, authRefreshes)
        opened.forEach { assertEquals(81000, it.position); assertEquals(4096, it.length); assertEquals(track.cacheKey, it.key) }
        source.close()
    }
    @Test fun repeatedDownloadUrl404IsNotProofOfADeletedFile() {
        var resolves = 0
        val source = ResolvingTrackSource({ track }, { ReadRequest("https://pdds.quark.cn/file?token=${++resolves}", emptyMap()) }, DataSource.Factory {
            object : EmptySource() {
                override fun open(dataSpec: DataSpec): Long = throw HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, emptyMap(), dataSpec, byteArrayOf())
            }
        })
        val error = runCatching { source.open(DataSpec.Builder().setUri("cruisetune://track/song").setKey(track.cacheKey).build()) }.exceptionOrNull()
        assertTrue(error is HttpDataSource.InvalidResponseCodeException)
        assertNull(confirmedRemoteFileMissing(error))
        assertEquals(2, resolves)
    }
    @Test fun providerConfirmationAfterA404IsExposedAsAMissingFile() {
        var resolves = 0
        val source = ResolvingTrackSource({ track }, {
            if (++resolves == 2) throw ConfirmedRemoteFileMissing()
            ReadRequest("https://pdds.quark.cn/file", emptyMap())
        }, DataSource.Factory {
            object : EmptySource() {
                override fun open(dataSpec: DataSpec): Long = throw HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, emptyMap(), dataSpec, byteArrayOf())
            }
        })
        val error = runCatching { source.open(DataSpec.Builder().setUri("cruisetune://track/song").setKey(track.cacheKey).build()) }.exceptionOrNull()
        assertTrue(error is ConfirmedRemoteFileMissing)
        assertEquals(2, resolves)
    }
    @Test fun persistentAuthorizationErrorHasBoundedRetry() {
        var attempts = 0
        val source = ResolvingTrackSource({ track }, { ReadRequest("https://pdds.quark.cn/file", emptyMap()) }, DataSource.Factory {
            object : EmptySource() {
                override fun open(dataSpec: DataSpec): Long { attempts++; throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, byteArrayOf()) }
            }
        })
        assertTrue(runCatching { source.open(DataSpec.Builder().setUri("cruisetune://track/song").setKey(track.cacheKey).build()) }.isFailure)
        assertEquals(2, attempts)
    }
    private open class EmptySource : DataSource {
        override fun open(dataSpec: DataSpec): Long = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = null
        override fun close() = Unit
        override fun addTransferListener(transferListener: TransferListener) = Unit
    }
}
