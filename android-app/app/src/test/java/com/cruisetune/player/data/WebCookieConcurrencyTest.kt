package com.cruisetune.player.data

import com.cruisetune.player.core.UserError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class WebCookieConcurrencyTest {
    private val page = """{"status":200,"code":0,"data":{"list":[]},"metadata":{"_total":0}}"""
    private class Store {
        var value: String? = "__pus=account; __puus=old-session"
        var revision = 0
        @Synchronized fun read() = value
        @Synchronized fun generation() = revision
        @Synchronized fun put(value: String?) { this.value = value; revision++ }
    }
    private fun api(server: MockWebServer, store: Store) = QuarkApi(OkHttpClient(), store::read, store::put, server.url("/"), store, store::generation)
    private suspend fun received(server: MockWebServer) = withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
    @Test fun oldResponsesCannotOverwriteANewLoginEvenWhenItsCookieTextIsIdentical() = runBlocking {
        for (sameValue in listOf(false, true)) MockWebServer().use { server ->
            server.start(); val store = Store(); val api = api(server, store)
            val pending = async(Dispatchers.IO) { api.listChildren("root") }
            assertTrue(received(server).getHeader("Cookie")!!.contains("old-session"))
            val fresh = if (sameValue) store.read()!! else "__pus=account; __puus=new-session"
            store.put(fresh)
            server.enqueue(MockResponse().setHeader("Set-Cookie", "__puus=stale-rotation; Path=/").setBody(page))
            server.enqueue(MockResponse().setBody(page))
            pending.await()
            assertEquals(fresh, store.read())
            assertEquals(fresh, received(server).getHeader("Cookie"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun aLateResponseCannotResurrectADisconnectedAccount() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(); val api = api(server, store)
            val pending = async(Dispatchers.IO) { runCatching { api.listChildren("root") } }
            received(server); store.put(null)
            server.enqueue(MockResponse().setHeader("Set-Cookie", "__puus=stale; Path=/").setBody(page))
            val error = pending.await().exceptionOrNull()
            assertTrue(error is UserError && error.needsLogin)
            assertNull(store.read()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun simultaneousApisShareTheSameAtomicRevisionGuard() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(); val first = api(server, store); val second = api(server, store)
            val gate = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val folder = request.requestUrl!!.queryParameter("pdir_fid")
                    val old = request.getHeader("Cookie")!!.contains("old-session")
                    if (folder == "slow" && old) {
                        check(gate.await(5, TimeUnit.SECONDS))
                        return MockResponse().setHeader("Set-Cookie", "__puus=late-old; Path=/").setBody(page)
                    }
                    return if (folder == "fast") MockResponse().setHeader("Set-Cookie", "__puus=rotated; Path=/").setBody(page)
                    else MockResponse().setBody(page)
                }
            }
            val slow = async(Dispatchers.IO) { first.listChildren("slow") }; received(server)
            try {
                second.listChildren("fast"); assertTrue(store.read()!!.contains("rotated"))
            } finally { gate.countDown() }
            slow.await()
            assertEquals("__pus=account; __puus=rotated", store.read())
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun obsoleteAuthorizationFailureUsesTheNewSession() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(); val api = api(server, store)
            val pending = async(Dispatchers.IO) { api.listChildren("root") }; received(server)
            store.put("__puus=new-session")
            server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody(page))
            assertTrue(pending.await().isEmpty()); assertEquals("__puus=new-session", store.read())
        }
    }
}
