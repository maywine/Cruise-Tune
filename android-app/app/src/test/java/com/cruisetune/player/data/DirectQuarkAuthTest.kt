package com.cruisetune.player.data

import com.cruisetune.player.core.UserError
import com.cruisetune.player.data.open.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectQuarkAuthTest {
    private fun MockWebServer.reply(data:JSONObject) = enqueue(MockResponse().setBody(JSONObject().put("status",0).put("data",data).toString()))
    private fun tokens(device:String="device") = JSONObject().put("status","confirmed").put("user_id","user").put("device_id",device).put("access_token","access").put("refresh_token","refresh").put("access_token_expires_at",2000000000)
    @Test fun createPollExchangeBindsIdentityAndParsesExpiry() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api=DirectQuarkAuth(OkHttpClient(),"test-client","test-sign","install",server.url("/")){1000}
            server.reply(JSONObject().put("authorize_page_url","https://pan.quark.cn/open/v1/oauth/agent?page_code=page#/agent-oauth").put("page_code","page").put("device_id","device"))
            val challenge=api.create(); val request=server.takeRequest()
            assertEquals("test-client",request.getHeader("x-pan-client-id")); assertEquals("install",JSONObject(request.body.readUtf8()).getString("client_device_id"))
            assertTrue(challenge.url.contains("agent_id=cruise-tune"))
            server.reply(JSONObject().put("status","waiting")); assertNull(api.poll(challenge))
            server.reply(JSONObject().put("status","success").put("agent_auth_code","AAC-1234567890123456")); assertEquals("AAC-1234567890123456",api.poll(challenge))
            server.reply(tokens()); val session=api.complete(challenge,"AAC-1234567890123456")
            assertEquals(2000000000000,session.expiresAtMs);assertEquals(OpenSession.id("test-client","user"),session.accountId)
        }
    }
    @Test fun refusesMismatchedAuthorizationPage() = runBlocking {
        MockWebServer().use { s ->
            s.start(); val api=DirectQuarkAuth(OkHttpClient(),"client","sign","install",s.url("/"))
            s.reply(JSONObject().put("authorize_page_url","https://pan.quark.cn/open/v1/oauth/agent?page_code=other").put("page_code","page").put("device_id","device"))
            try {api.create();fail("mismatched page accepted")}catch(_:UserError){}
        }
    }
    @Test fun refusesAnotherDevicesTokenAndExpiredCode() = runBlocking {
        MockWebServer().use { s ->
            s.start(); val api=DirectQuarkAuth(OkHttpClient(),"client","sign","install",s.url("/"))
            val c=DirectQuarkAuth.Challenge("https://pan.quark.cn/","page","device")
            for(d in listOf(tokens("another-device"),tokens().put("status","expired"))) {
                s.reply(d);try{api.complete(c,"AAC-1234567890123456");fail("invalid exchange accepted")}catch(_:UserError){}
            }
        }
    }
    @Test fun refreshReplacesBothTokensAndKeepsAccountIdentity() = runBlocking {
        MockWebServer().use { s ->
            s.start();val api=DirectQuarkAuth(OkHttpClient(),"client","sign","install",s.url("/")){1000}
            val old=OpenSession(OpenSession.id("client","user"),DirectQuarkAuth.PROFILE,"client","user","device","old-a","old-r",0)
            s.reply(JSONObject().put("access_token","new-a").put("refresh_token","new-r").put("expires_in",7200))
            val next=api.rotate(old);assertEquals(old.accountId,next.accountId);assertEquals("new-r",next.refreshToken);assertEquals(7201000,next.expiresAtMs)
            val body=JSONObject(s.takeRequest().body.readUtf8());assertEquals("old-r",body.getString("refresh_token"));assertEquals("device",body.getString("device_id"))
        }
    }
}
