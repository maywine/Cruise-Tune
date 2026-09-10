package com.cruisetune.player.data

import com.cruisetune.player.core.UserError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class QuarkQrAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: QuarkQrAuth
    @Before fun setup() {
        server = MockWebServer(); server.start()
        val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }).build()
        auth = QuarkQrAuth(client, server.url("/cas/ajax/"), server.url("/account/info").newBuilder().host("pan.quark.cn").build(), server.url("/config").newBuilder().host("drive.quark.cn").build())
    }
    @After fun tearDown() { server.shutdown() }
    @Test fun challengeEncodesTokenWithoutTreatingItAsQuerySyntax() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":2000000,"data":{"members":{"token":"token&other=1"}}}"""))
        val challenge = auth.create()
        assertEquals("token&other=1", challenge.token)
        assertTrue(challenge.url.contains("token=token%26other%3D1"))
        assertEquals("532", server.takeRequest().requestUrl!!.queryParameter("client_id"))
    }
    @Test fun waitingExpiredAndConfirmedAreDistinctStates() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":50004001}"""))
        server.enqueue(MockResponse().setBody("""{"status":50004002}"""))
        server.enqueue(MockResponse().setBody("""{"status":2000000,"message":"ok","data":{"members":{"service_ticket":"ticket"}}}"""))
        val challenge = QrChallenge("token", "https://su.quark.cn/")
        assertEquals(QrLoginState.Waiting, auth.poll(challenge))
        assertEquals(QrLoginState.Expired, auth.poll(challenge))
        assertEquals(QrLoginState.Confirmed("ticket"), auth.poll(challenge))
    }
    @Test fun ticketExchangeCollectsOnlyQuarkCookiesAndBootstrapsDrive() = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "__kps=account; Domain=.quark.cn; Path=/").setBody("{}"))
        server.enqueue(MockResponse().addHeader("Set-Cookie", "__puus=drive-session; Domain=.quark.cn; Path=/").setBody("{}"))
        val cookie = auth.exchange("ticket")
        assertTrue(cookie.contains("__kps=account")); assertTrue(cookie.contains("__puus=drive-session"))
        assertEquals("ticket", server.takeRequest().requestUrl!!.queryParameter("st"))
        assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("__kps=account"))
    }
    @Test fun successfulStatusWithoutTokenIsNotAccepted() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":2000000,"data":{"members":{}}}"""))
        assertTrue(runCatching { auth.create() }.exceptionOrNull() is UserError)
    }
    @Test fun unrecognizedPollingResponseStopsRatherThanPollingForever() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":9999,"message":"private response"}"""))
        val error = runCatching { auth.poll(QrChallenge("secret", "")) }.exceptionOrNull()
        assertTrue(error is UserError); assertFalse(error!!.message!!.contains("secret"))
    }
}
