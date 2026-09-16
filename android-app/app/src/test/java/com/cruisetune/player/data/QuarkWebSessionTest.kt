package com.cruisetune.player.data

import com.cruisetune.player.core.UserError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class QuarkWebSessionTest {
    private val page = """{"status":200,"code":0,"data":{"list":[]},"metadata":{"_total":0}}"""
    @Test fun rejectedDownloadDoesNotOverwriteOldCredentialEvenAfterCookieRotation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Set-Cookie", "__puus=rotated; Path=/").setBody(page))
            server.enqueue(MockResponse().setBody(page))
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-server-data"))
            var stored = "old-session"
            val error = runCatching { QuarkWebSession.connect("__puus=new-session", listOf("folder"), listOf("song"),
                { read, save -> QuarkApi(OkHttpClient(), read, save, server.url("/")) }, { stored = it }) }.exceptionOrNull()
            assertTrue(error is UserError && error.needsLogin)
            assertEquals("old-session", stored)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun reconnectCommitsValidatedRotatedCookieOnlyAfterOriginalFileCanDownload() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(page))
            server.enqueue(MockResponse().setBody(page))
            server.enqueue(MockResponse().setHeader("Set-Cookie", "__puus=rotated; Path=/").setBody(
                """{"status":200,"code":0,"data":[{"fid":"song","download_url":"https://pdds.quark.cn/song"}]}"""))
            var stored = "old-session"
            QuarkWebSession.connect("__puus=new-session", listOf("folder"), listOf("song"),
                { read, save -> QuarkApi(OkHttpClient(), read, save, server.url("/")) }, { stored = it })
            assertEquals("__puus=rotated", stored)
            server.takeRequest()
            assertEquals("folder", server.takeRequest().requestUrl!!.queryParameter("pdir_fid"))
            assertTrue(server.takeRequest().body.readUtf8().contains("song"))
        }
    }
}
