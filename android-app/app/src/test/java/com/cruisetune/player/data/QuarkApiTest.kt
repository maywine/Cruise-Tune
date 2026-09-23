package com.cruisetune.player.data

import com.cruisetune.player.core.UserError
import com.cruisetune.player.core.ConfirmedRemoteFileMissing
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuarkApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: QuarkApi
    private var cookie = "__pus=account; __puus=old-session"
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = QuarkApi(OkHttpClient(), { cookie }, { cookie = it }, server.url("/1/clouddrive/"))
    }
    @After fun tearDown() { server.shutdown() }
    private fun file(id: String, directory: Boolean = false) = JSONObject().put("fid", id).put("file_name", "$id.flac").put("file", !directory).put("size", 100).put("updated_at", 1234)
    private fun page(files: List<JSONObject>, total: Int) = JSONObject().put("status", 200).put("code", 0)
        .put("data", JSONObject().put("list", JSONArray(files))).put("metadata", JSONObject().put("_total", total)).toString()
    @Test fun listsAllPagesAndRecognizesDirectoryFlag() = runBlocking {
        server.enqueue(MockResponse().setBody(page((0..99).map { file(it.toString(), it == 0) }, 101)))
        server.enqueue(MockResponse().setBody(page(listOf(file("100")), 101)))
        val result = api.listChildren("folder id")
        assertEquals(101, result.size); assertTrue(result[0].isDirectory)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("folder id", first.requestUrl!!.queryParameter("pdir_fid"))
        assertEquals("2", second.requestUrl!!.queryParameter("_page"))
        assertEquals(cookie, first.getHeader("Cookie"))
        assertFalse(first.path!!.contains("old-session"))
    }
    @Test fun repeatingPageFailsRatherThanReturningIncompleteLibrary() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody(page((0..99).map { file(it.toString()) }, 200))) }
        val error = runCatching { api.listChildren("0") }.exceptionOrNull()
        assertTrue(error is UserError)
        assertTrue(error!!.message!!.contains("分页"))
    }
    @Test fun expiredSessionProducesLoginActionWithoutLeakingResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private-account-token"))
        val error = runCatching { api.listChildren("0") }.exceptionOrNull() as UserError
        assertTrue(error.needsLogin); assertFalse(error.message!!.contains("private"))
    }
    @Test fun rotatedCookieIsRetainedAndSentOnNextRequest() = runBlocking {
        server.enqueue(MockResponse().setHeader("Set-Cookie", "__puus=new-session; Path=/").setBody(page(emptyList(), 0)))
        server.enqueue(MockResponse().setBody(page(emptyList(), 0)))
        api.listChildren("0"); api.listChildren("0")
        assertTrue(cookie.contains("__puus=new-session")); assertTrue(cookie.contains("__pus=account"))
        server.takeRequest(); assertEquals(cookie, server.takeRequest().getHeader("Cookie"))
    }
    @Test fun freshDownloadLinkIsObtainedEachTimeWithoutChangingFileId() = runBlocking {
        repeat(2) { n -> server.enqueue(MockResponse().setBody("""{"status":200,"code":0,"data":[{"fid":"song","download_url":"https://pdds.quark.cn/song?signature=$n"}]}""")) }
        val first = api.resolve("song"); val second = api.resolve("song")
        assertNotEquals(first.url, second.url)
        assertEquals(cookie, second.headers["Cookie"])
        repeat(2) { assertEquals("song", JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("fids").getString(0)) }
    }
    @Test fun twoSuccessfulEmptyDownloadResultsConfirmTheFileIsMissing() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"status":200,"code":0,"data":[]}""")) }
        val error = runCatching { api.resolve("removed-song") }.exceptionOrNull()
        assertTrue(error is ConfirmedRemoteFileMissing)
        assertEquals(2, server.requestCount)
    }
    @Test fun aTemporaryEmptyDownloadResultDoesNotSkipAReturningFile() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":200,"code":0,"data":[]}"""))
        server.enqueue(MockResponse().setBody("""{"status":200,"code":0,"data":[{"fid":"song","download_url":"https://pdds.quark.cn/song"}]}"""))
        assertEquals("https://pdds.quark.cn/song", api.resolve("song").url)
        assertEquals(2, server.requestCount)
    }
    @Test fun aLoginFailureDuringConfirmationIsNotCalledADeletedFile() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":200,"code":0,"data":[]}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runCatching { api.resolve("song") }.exceptionOrNull()
        assertTrue(error is UserError && error.needsLogin)
        assertEquals(2, server.requestCount)
    }
    @Test fun aMismatchedDownloadResultIsNotCalledADeletedFile() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":200,"code":0,"data":[{"fid":"another-song","download_url":"https://pdds.quark.cn/another"}]}"""))
        assertTrue(runCatching { api.resolve("song") }.exceptionOrNull() is UserError)
        assertEquals(1, server.requestCount)
    }
    @Test fun webAudioUsesVerifiedPcProfileAndPassesRotatedCookieToRangeReader() = runBlocking {
        server.enqueue(MockResponse().setBody(page(emptyList(), 0)))
        server.enqueue(MockResponse().setHeader("Set-Cookie", "__puus=pc-session; Path=/").setBody(
            """{"status":200,"code":0,"data":[{"fid":"large-flac","download_url":"https://pdds.quark.cn/audio.flac"}]}"""))
        api.listChildren("0")
        val read = api.resolve("large-flac")
        assertEquals(QuarkApi.USER_AGENT, server.takeRequest().getHeader("User-Agent"))
        val download = server.takeRequest()
        assertEquals("/1/clouddrive/file/download", download.requestUrl!!.encodedPath)
        assertEquals("win32", download.requestUrl!!.queryParameter("sys"))
        assertEquals(QuarkApi.PC_CLIENT_VERSION, download.requestUrl!!.queryParameter("ve"))
        assertEquals(QuarkApi.PC_DOWNLOAD_USER_AGENT, download.getHeader("User-Agent"))
        assertEquals(QuarkApi.PC_DOWNLOAD_USER_AGENT, read.headers["User-Agent"])
        assertTrue(read.headers.getValue("Cookie").contains("__puus=pc-session"))
    }
    @Test fun remainingSizeRestrictionIsNotMisreportedAsNetworkOrLoginFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":400,"code":23018,"message":"private diagnostic details"}"""))
        val error = runCatching { api.resolve("large-flac") }.exceptionOrNull() as UserError
        assertEquals("夸克限制了该文件下载，请检查网盘下载权限", error.message)
        assertFalse(error.retryable); assertFalse(error.needsLogin)
        assertEquals(1, server.requestCount)
    }
    @Test fun downloadCredentialsNeverGoToUnexpectedHost() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":[{"download_url":"https://quark.cn.attacker.example/song"}]}"""))
        assertTrue(runCatching { api.resolve("song") }.exceptionOrNull() is UserError)
    }
    @Test fun malformedDirectoryResponseIsNotAnEmptySuccessfulScan() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":200,"code":0,"data":{}}"""))
        assertTrue(runCatching { api.listChildren("0") }.exceptionOrNull() is UserError)
    }
    @Test fun sessionHeaderRejectsNewlineInjection() {
        assertFalse(QuarkApi.validCookie("__puus=ok\r\nX-Extra: value"))
        assertFalse(QuarkApi.validCookie("not-a-quark-session"))
        assertTrue(QuarkApi.validCookie("__puus=valid-session"))
    }
    @Test fun invalidCookieRotationDoesNotReplaceTheSavedSession() = runBlocking {
        cookie = "__puus=valid-session"
        server.enqueue(MockResponse().setHeader("Set-Cookie", "__puus=; Max-Age=0; Path=/").setBody(page(emptyList(), 0)))
        val error = runCatching { api.listChildren("0") }.exceptionOrNull()
        assertTrue(error is UserError && error.needsLogin)
        assertEquals("__puus=valid-session", cookie)
    }
    @Test fun rateLimitWaitsThenRetriesTheSameDirectory() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody(page(listOf(file("song")), 1)))
        assertEquals("song", api.listChildren("folder").single().id)
        repeat(2) { assertEquals("folder", server.takeRequest().requestUrl!!.queryParameter("pdir_fid")) }
    }
    @Test fun repeatedRateLimitStopsAfterThreeAttempts() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }
        assertTrue(runCatching { api.listChildren("0") }.exceptionOrNull() is UserError)
        assertEquals(3, server.requestCount)
    }
}
