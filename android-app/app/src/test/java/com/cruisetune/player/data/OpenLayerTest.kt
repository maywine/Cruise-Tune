package com.cruisetune.player.data

import com.cruisetune.player.core.*
import com.cruisetune.player.data.open.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OpenLayerTest {
    private fun session() = OpenSession(OpenSession.id("player-client", "user-1"), "profile", "player-client", "user-1", "device", "access-1", "refresh-1", Long.MAX_VALUE)
    private class Store(initial: OpenSession) : OpenSessionStore {
        var value: OpenSession? = initial
        var fail = false
        @Synchronized override fun read(accountId: String) = value?.takeIf { it.accountId == accountId }
        @Synchronized override fun write(session: OpenSession) { if (fail) throw java.io.IOException("disk full"); value = session }
        @Synchronized override fun remove(accountId: String) { value = null }
    }
    private open class Broker : OpenBroker {
        val rotations = AtomicInteger()
        override suspend fun begin(profile: OpenProfile, state: String) = error("unused")
        override suspend fun exchange(profile: OpenProfile, code: String): OpenSession = error("unused")
        override suspend fun rotate(session: OpenSession): OpenSession { rotations.incrementAndGet(); delay(10); return session.copy(accessToken = "access-new", refreshToken = "refresh-new", expiresAtMs = Long.MAX_VALUE) }
        override suspend fun sign(session: OpenSession, method: String, path: String) = mapOf("x-pan-client-id" to session.clientId, "x-pan-tm" to "1", "x-pan-token" to "signed")
    }
    @Test fun concurrentRefreshUsesOneRequestAndPersistsTokenPair() = runBlocking {
        val original = session().copy(expiresAtMs = 1)
        val store = Store(original); val broker = Broker(); val manager = OpenSessionManager(store, broker)
        val results = coroutineScope { (1..20).map { async { manager.fresh(original.accountId) } }.awaitAll() }
        assertEquals(1, broker.rotations.get())
        assertTrue(results.all { it.accessToken == "access-new" && it.refreshToken == "refresh-new" })
        assertEquals(1, store.value!!.generation)
        manager.refreshAfterFailure(original.accountId, 0)
        assertEquals(1, broker.rotations.get())
    }
    @Test fun staleHeaderCannotOverwriteNewRotation() = runBlocking {
        val s = session(); val store = Store(s); val manager = OpenSessionManager(store, Broker())
        manager.refreshAfterFailure(s.accountId, 0)
        manager.acceptServerToken(s.accountId, 0, "late-old-token")
        assertEquals("access-new", store.value!!.accessToken)
    }
    @Test fun failedDurableWriteDoesNotExposeNewToken() = runBlocking {
        val s = session().copy(expiresAtMs = 1); val store = Store(s).apply { fail = true }
        assertTrue(runCatching { OpenSessionManager(store, Broker()).fresh(s.accountId) }.isFailure)
        assertEquals("access-1", store.value!!.accessToken)
    }
    @Test fun refreshCannotChangeAccountOrDeviceBinding() = runBlocking {
        val s = session(); val store = Store(s)
        val broker = object : Broker() { override suspend fun rotate(session: OpenSession) = session.copy(deviceId = "other-device") }
        assertTrue(runCatching { OpenSessionManager(store, broker).refreshAfterFailure(s.accountId, 0) }.exceptionOrNull() is UserError)
        assertEquals("device", store.value!!.deviceId)
    }
    @Test fun sameAccountReauthorizationKeepsIdentityAndInvalidatesOldResponses() = runBlocking {
        val s = session(); val store = Store(s); val manager = OpenSessionManager(store, Broker())
        val next = manager.activate(s.copy(accessToken = "reauthorized", refreshToken = "new-pair"))
        assertEquals(s.accountId, next.accountId); assertEquals(1, next.generation)
        manager.acceptServerToken(s.accountId, 0, "stale")
        assertEquals("reauthorized", store.value!!.accessToken)
        manager.disconnect(s.accountId)
        assertTrue(runCatching { manager.acceptServerToken(s.accountId, 1, "late") }.isFailure)
        assertNull(store.value)
    }
    private fun file(fid: String, name: String = "song.flac") = JSONObject().put("fid", fid).put("filename", name).put("file_type", "1").put("size", 120).put("updated_at", 88)
    private fun page(files: JSONArray?, last: Boolean, cursor: JSONObject? = null, gap: Int = 0) = JSONObject().put("status", 0).put("metadata", JSONObject().put("tq_gap", gap)).put("data", JSONObject().put("file_list", files ?: JSONObject.NULL).put("last_page", last).apply { cursor?.let { put("next_query_cursor", it) }; put("total", 999999) }).toString()
    @Test fun cursorTraversesEmptyAndDuplicatePagesWithoutUsingTotal() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(page(null, false, JSONObject().put("version", "v1").put("token", "a"), 123)))
            server.enqueue(MockResponse().setBody(page(JSONArray().put(file("scope|id")), false, JSONObject().put("version", "v1").put("token", "b"))))
            server.enqueue(MockResponse().setBody(page(JSONArray().put(file("new-scope|id")), true)))
            val s = session(); val pauses = mutableListOf<Long>(); val broker = Broker()
            val api = QuarkOpenApi(s.accountId, OpenSessionManager(Store(s), broker), broker, OkHttpClient(), server.url("/"), { pauses += it })
            val files = api.listChildren("full|folder")
            assertEquals(1, files.size); assertEquals("new-scope|id", files[0].id); assertEquals(listOf(123L), pauses)
            val first = JSONObject(server.takeRequest().body.readUtf8()); assertEquals("full|folder", first.getString("parent_fid")); assertFalse(first.has("query_cursor"))
            assertEquals("a", JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("query_cursor").getString("token"))
        } finally { server.shutdown() }
    }
    @Test fun repeatedCursorFailsEvenWhenPageIsEmpty() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setBody(page(JSONArray(), false, JSONObject().put("version", "1").put("token", "loop")))) }
            val s = session(); val b = Broker(); val api = QuarkOpenApi(s.accountId, OpenSessionManager(Store(s), b), b, OkHttpClient(), server.url("/"))
            assertTrue(runCatching { api.listChildren("0") }.exceptionOrNull() is UserError)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun authExpiryReplaysOnceWithNewTokenAndExactFid() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":-1,"errno":11001}"""))
            server.enqueue(MockResponse().setBody("""{"status":0,"data":{"fid":"scope|song","download_url":"https://pdds.quark.cn/song?expires=1"}}"""))
            val s = session(); val b = Broker(); val api = QuarkOpenApi(s.accountId, OpenSessionManager(Store(s), b), b, OkHttpClient(), server.url("/"))
            val request = api.resolve("scope|song")
            assertEquals(1, b.rotations.get()); assertEquals(1L, request.credentialGeneration)
            assertTrue(request.headers.getValue("Cookie").contains("x_pan_access_token=access-new"))
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals("access-1", first.requestUrl!!.queryParameter("access_token")); assertEquals("access-new", second.requestUrl!!.queryParameter("access_token"))
            assertEquals("scope|song", JSONObject(second.body.readUtf8()).getString("fid"))
        } finally { server.shutdown() }
    }
    @Test fun serverTokenHeaderIsUsedByDownloadCredentials() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("x-new-access-token", "server-new").setBody("""{"status":0,"data":{"fid":"song","download_url":"https://pdds.quark.cn/song"}}"""))
            val s = session(); val store = Store(s); val b = Broker()
            val result = QuarkOpenApi(s.accountId, OpenSessionManager(store, b), b, OkHttpClient(), server.url("/")).resolve("song")
            assertTrue(result.headers.getValue("Cookie").contains("server-new")); assertEquals("server-new", store.value!!.accessToken)
        } finally { server.shutdown() }
    }
    @Test fun fileSizeRestrictionExplainsServerLimitWithoutRotatingOrRetrying() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody(
                """{"status":-1,"errno":23018,"error_info":"download file size limit[52428800]"}"""))
            val s = session(); val broker = Broker()
            val api = QuarkOpenApi(s.accountId, OpenSessionManager(Store(s), broker), broker, OkHttpClient(), server.url("/"))
            val error = runCatching { api.resolve("scope|song") }.exceptionOrNull() as UserError
            assertEquals("文件超过夸克当前的下载上限（50 MiB）", error.message)
            assertFalse(error.retryable); assertFalse(error.needsLogin)
            assertEquals(0, broker.rotations.get()); assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun unknownFileLimitMessageDoesNotExposeRawServiceText() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody(
                """{"status":-1,"errno":23018,"error_info":"private signed URL or account details"}"""))
            val s = session(); val b = Broker()
            val error = runCatching { QuarkOpenApi(s.accountId, OpenSessionManager(Store(s), b), b, OkHttpClient(), server.url("/")).resolve("scope|song") }.exceptionOrNull() as UserError
            assertEquals("夸克限制了该文件下载，请检查网盘下载权限", error.message)
            assertFalse(error.retryable); assertFalse(error.needsLogin)
        } finally { server.shutdown() }
    }
    @Test fun malformedLastPageAndBadDownloadHostFailClosed() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":0,"data":{"file_list":[],"last_page":"true"}}"""))
            server.enqueue(MockResponse().setBody("""{"status":0,"data":{"download_url":"https://evil.example/song"}}"""))
            val s = session(); val b = Broker(); val api = QuarkOpenApi(s.accountId, OpenSessionManager(Store(s), b), b, OkHttpClient(), server.url("/"))
            assertTrue(runCatching { api.listChildren("0") }.exceptionOrNull() is UserError)
            assertTrue(runCatching { api.resolve("song") }.exceptionOrNull() is UserError)
        } finally { server.shutdown() }
    }
    @Test fun fileWrapperChangesDoNotChangeCacheIdentityAndOldJsonStillLoads() {
        val a = Track("song", "source", "old|id", "song", size = 10, version = "hash:1", contentIdentity = openFileIdentity("old|id"))
        val b = a.copy(fileId = "new|id")
        assertEquals(a.cacheKey, b.cacheKey)
        assertNotEquals(a.cacheKey, b.copy(version = "hash:2").cacheKey)
        assertEquals("a|", openFileIdentity("a|"))
        val old = JSONObject(JsonCodec.encode(a.copy(contentIdentity = ""))).apply { remove("identity") }.toString()
        assertEquals("", JsonCodec.decode(old).contentIdentity)
    }
    @Test fun importedConnectionRejectsSkillIdentityAndApplicationSecrets() {
        val valid = JSONObject().put("clientId", "player-client").put("serviceUrl", "https://service.example/").put("serviceToken", "test-connection-token-1234567890")
        assertEquals("player-client", OpenProfile.parse(valid.toString()).clientId)
        assertTrue(runCatching { OpenProfile.parse(JSONObject(valid.toString()).put("clientId", "third_party_agent").toString()) }.isFailure)
        assertTrue(runCatching { OpenProfile.parse(JSONObject(valid.toString()).put("signKey", "do-not-import").toString()) }.isFailure)
        assertTrue(runCatching { OpenProfile.parse(JSONObject(valid.toString()).put("serviceUrl", "http://service.example").toString()) }.isFailure)
    }
}
