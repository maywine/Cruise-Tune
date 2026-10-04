package com.cruisetune.player

import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cruisetune.player.core.Track
import com.cruisetune.player.core.UserError
import com.cruisetune.player.data.open.*
import com.cruisetune.player.playback.MediaCache
import com.cruisetune.player.playback.prefetchBufferReady
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
@androidx.media3.common.util.UnstableApi
class GateRegressionDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as CruiseApplication
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)

    @Test fun fixedGatesAllowValidWorkWithoutAcceptingStaleCredentials() {
        check(app.packageName.endsWith(".authcheck")) { "Use the isolated validation application" }
        check(app.database.sources().isEmpty() && app.database.restore().entries.isEmpty()) { "Use an empty test library and queue" }
        tokenGeneration()
        pcmBuffer()
        offlineCapacity()
        assertTrue(app.database.sources().isEmpty())
        assertTrue(app.database.restore().entries.isEmpty())
    }

    private fun tokenGeneration() = runBlocking {
        val original = OpenSession(OpenSession.id("synthetic-client", "synthetic-user"), "synthetic-profile",
            "synthetic-client", "synthetic-user", "synthetic-device", "old-access", "old-refresh", Long.MAX_VALUE)
        var saved: OpenSession? = original
        val store = object : OpenSessionStore {
            override fun read(accountId: String) = saved?.takeIf { it.accountId == accountId }
            override fun write(session: OpenSession) { saved = session }
            override fun remove(accountId: String) { saved = null }
        }
        val broker = object : OpenBroker {
            override suspend fun begin(profile: OpenProfile, state: String): String = error("Unexpected network authorization")
            override suspend fun exchange(profile: OpenProfile, code: String): OpenSession = error("Unexpected network exchange")
            override suspend fun rotate(session: OpenSession): OpenSession = error("Stale failures must not rotate a new login")
            override suspend fun sign(session: OpenSession, method: String, path: String): Map<String, String> = error("Unexpected network signing")
        }
        val manager = OpenSessionManager(store, broker)
        val outstanding = manager.fresh(original.accountId)
        manager.disconnect(original.accountId)
        val renewed = manager.activate(original.copy(accessToken = "new-access", refreshToken = "new-refresh"))
        manager.acceptServerToken(original.accountId, outstanding.generation, "stale-access")
        manager.refreshAfterFailure(original.accountId, outstanding.generation)
        assertTrue(renewed.generation > outstanding.generation)
        assertEquals(renewed, saved)
    }

    private fun pcmBuffer() {
        val highResolution = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(192000).setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_24BIT).build()
        val ordinary = highResolution.buildUpon().setSampleRate(44100).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        assertTrue(prefetchBufferReady(10_000, highResolution))
        assertFalse(prefetchBufferReady(1_000, highResolution))
        assertFalse(prefetchBufferReady(8_000, ordinary))
        assertTrue(prefetchBufferReady(15_000, ordinary))
        assertFalse(prefetchBufferReady(8_000, null))
    }

    private fun offlineCapacity() {
        lateinit var media: MediaCache
        main { media = app.media }
        val total = StatFs(app.filesDir.absolutePath).totalBytes + 32 * 1024 * 1024L
        val selected = Track("gate-offline-${SystemClock.elapsedRealtimeNanos()}", "synthetic-gates", "synthetic-file",
            "Synthetic capacity probe", size = total)
        val cache = media.offline
        var failure: Throwable? = null
        try {
            main { failure = runCatching { media.keepOffline(selected) }.exceptionOrNull() }
            assertTrue("An uncached file larger than available storage must be rejected", failure is UserError)
            val cachedBytes = total - 16 * 1024
            val hole = cache.startReadWrite(selected.cacheKey, 0, cachedBytes)
            try {
                val file = cache.startFile(selected.cacheKey, 0, cachedBytes)
                var committed = false
                try {
                    RandomAccessFile(file, "rw").use { it.setLength(cachedBytes) }
                    assertTrue("This device test requires sparse files", Os.stat(file.absolutePath).st_blocks * 512 < 1024 * 1024)
                    cache.commitFile(file, cachedBytes)
                    committed = true
                } finally { if (!committed) file.delete() }
            } finally { cache.releaseHoleSpan(hole) }
            val metadata = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(metadata, total)
            cache.applyContentMetadataMutations(selected.cacheKey, metadata)
            assertEquals(16 * 1024L, total - cache.getCachedBytes(selected.cacheKey, 0, total))
            assertTrue(StatFs(app.filesDir.absolutePath).availableBytes - media.reserveBytes > 16 * 1024)
            main { failure = runCatching { media.keepOffline(selected) }.exceptionOrNull() }
            assertNull("The existing sparse cache leaves only 16 KiB to allocate", failure)
            await("The resumed request must reach the download manager") { app.offlineIndex.getDownload(selected.cacheKey) != null }
        } finally {
            main { media.downloadManager.removeDownload(selected.cacheKey) }
            await("The synthetic download must be removed") { app.offlineIndex.getDownload(selected.cacheKey) == null }
            cache.removeResource(selected.cacheKey)
            assertEquals(0L, cache.getCachedBytes(selected.cacheKey, 0, total))
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }
}
