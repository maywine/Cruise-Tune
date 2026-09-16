package com.cruisetune.player.data

import com.cruisetune.player.core.UserError
import com.cruisetune.player.data.open.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAuthorizationTest {
    @Test fun callbackStateSurvivesRecreationAndIsSingleUse() {
        var stored: String? = null
        val first = PendingAuthorization({ stored }, { stored = it }, { stored = null }, { 1000 })
        val state = first.create("profile")
        val recreated = PendingAuthorization({ stored }, { stored = it }, { stored = null }, { 2000 })
        assertTrue(runCatching { recreated.consume("wrong") }.exceptionOrNull() is UserError)
        assertEquals("profile", recreated.consume(state))
        assertTrue(runCatching { recreated.consume(state) }.isFailure)
    }
    @Test fun replacedAndExpiredStatesAreRejected() {
        var stored: String? = null; var now = 1000L
        val pending = PendingAuthorization({ stored }, { stored = it }, { stored = null }, { now })
        val old = pending.create("old"); val current = pending.create("new")
        assertNotEquals(old, current); assertTrue(runCatching { pending.consume(old) }.isFailure)
        now += 600001
        assertTrue(runCatching { pending.consume(current) }.isFailure)
    }
    @Test fun failedStateDeletionCannotProceedToExchange() {
        var stored: String? = null
        val pending = PendingAuthorization({ stored }, { stored = it }, { throw java.io.IOException("disk") })
        val state = pending.create("profile")
        assertTrue(runCatching { pending.consume(state) }.isFailure)
    }
    private fun validUrl(state: String) = "https://pan.quark.cn/open/v1/oauth/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", "player-client").addQueryParameter("response_type", "code")
        .addQueryParameter("state", state).addQueryParameter("redirect_uri", OpenProfile.CALLBACK).build().toString()
    @Test fun brokerAuthorizationChecksStateClientAndRedirect() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val profile = OpenProfile("profile", "player-client", server.url("/").toString(), "test-service-token-123456789")
            val broker = HttpOpenBroker(OkHttpClient()) { profile }
            server.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("data", JSONObject().put("authorizeUrl", validUrl("state"))).toString()))
            assertEquals(validUrl("state"), broker.begin(profile, "state"))
            assertEquals("Bearer ${profile.serviceToken}", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("data", JSONObject().put("authorizeUrl", validUrl("other"))).toString()))
            assertTrue(runCatching { broker.begin(profile, "state") }.exceptionOrNull() is UserError)
            server.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("data", JSONObject().put("authorizeUrl", validUrl("state") + "&client_id=other")).toString()))
            assertTrue(runCatching { broker.begin(profile, "state") }.isFailure)
        } finally { server.shutdown() }
    }
    @Test fun brokerRedirectDoesNotForwardConnectionCredential() = runBlocking {
        val first = MockWebServer(); val second = MockWebServer(); first.start(); second.start()
        try {
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/")))
            val p = OpenProfile("p", "client", first.url("/").toString(), "test-service-token-123456789")
            assertTrue(runCatching { HttpOpenBroker(OkHttpClient()) { p }.begin(p, "state") }.isFailure)
            assertEquals(0, second.requestCount)
        } finally { first.shutdown(); second.shutdown() }
    }
}
