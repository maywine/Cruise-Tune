package com.cruisetune.player.playback

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.cruisetune.player.core.InvalidMediaRange
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RangeValidationTest {
    private fun client() = OkHttpClient.Builder().addNetworkInterceptor(RangeValidationInterceptor()).build()
    @Test fun media3SafelySkipsFullResponseWhenServerIgnoresRange() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("0123456789abcdefghij"))
            val source = OkHttpDataSource.Factory(client()).createDataSource()
            assertEquals(5, source.open(DataSpec.Builder().setUri(server.url("/song").toString()).setPosition(10).setLength(5).build()))
            val bytes = ByteArray(5); assertEquals(5, source.read(bytes, 0, 5)); assertEquals("abcde", String(bytes))
            assertTrue(source.responseHeaders.entries.any { it.key.equals(RangeValidationInterceptor.SEQUENTIAL_HEADER, true) })
            source.close()
        } finally { server.shutdown() }
    }
    @Test fun incorrectPartialResponseIsRejectedBeforeDataIsConsumed() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-4/20").setBody("wrong"))
            val request = Request.Builder().url(server.url("/song")).header("Range", "bytes=10-14").build()
            assertTrue(runCatching { client().newCall(request).execute().close() }.exceptionOrNull() is InvalidMediaRange)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun correctPartialRangeAndLengthAreAccepted() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 10-14/20").setBody("abcde"))
            client().newCall(Request.Builder().url(server.url("/song")).header("Range", "bytes=10-14").build()).execute().use { assertEquals("abcde", it.body!!.string()) }
        } finally { server.shutdown() }
    }
    @Test fun mismatchedLengthOrAbsentContentRangeAreRejected() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 10-14/20").setBody("abc"))
            server.enqueue(MockResponse().setResponseCode(206).setBody("abcde"))
            repeat(2) { assertTrue(runCatching { client().newCall(Request.Builder().url(server.url("/song")).header("Range", "bytes=10-14").build()).execute().close() }.exceptionOrNull() is InvalidMediaRange) }
        } finally { server.shutdown() }
    }
}
