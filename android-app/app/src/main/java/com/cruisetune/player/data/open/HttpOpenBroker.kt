package com.cruisetune.player.data.open

import com.cruisetune.player.core.UserError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class HttpOpenBroker(client: OkHttpClient, private val profiles: (String) -> OpenProfile?) : OpenBroker {
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    override suspend fun begin(profile: OpenProfile, state: String): String {
        val j = call(profile, "authorize", JSONObject().put("state", state).put("redirectUri", OpenProfile.CALLBACK))
        val url = j.getString("authorizeUrl").toHttpUrl()
        if (url.scheme != "https" || url.host != "pan.quark.cn" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedPath != "/open/v1/oauth/authorize" || listOf("client_id", "state", "redirect_uri", "response_type").any { url.queryParameterValues(it).size != 1 } || url.queryParameter("client_id") != profile.clientId || url.queryParameter("state") != state || url.queryParameter("redirect_uri") != OpenProfile.CALLBACK || url.queryParameter("response_type") != "code") throw UserError("授权服务返回的登录地址不匹配")
        return url.toString()
    }
    override suspend fun exchange(profile: OpenProfile, code: String): OpenSession {
        val j = call(profile, "exchange", JSONObject().put("code", code).put("redirectUri", OpenProfile.CALLBACK))
        val userId = j.getString("userId")
        return OpenSession(OpenSession.id(profile.clientId, userId), profile.id, profile.clientId, userId,
            j.getString("deviceId"), j.getString("accessToken"), j.getString("refreshToken"), j.optLong("expiresAtMs"), clientToken = j.optString("clientToken"))
    }
    override suspend fun rotate(session: OpenSession): OpenSession {
        val j = call(profile(session), "rotate", JSONObject().put("refreshToken", session.refreshToken).put("deviceId", session.deviceId))
        return session.copy(accessToken = j.getString("accessToken"), refreshToken = j.getString("refreshToken"), expiresAtMs = j.optLong("expiresAtMs"), clientToken = j.optString("clientToken", session.clientToken))
    }
    override suspend fun sign(session: OpenSession, method: String, path: String): Map<String, String> {
        val j = call(profile(session), "sign", JSONObject().put("method", method).put("path", path))
        val clientId = j.getString("x-pan-client-id"); val timestamp = j.getString("x-pan-tm"); val token = j.getString("x-pan-token")
        if (clientId != session.clientId || timestamp.toLongOrNull() == null || !token.matches(Regex("[a-fA-F0-9]{64}"))) throw UserError("授权服务返回的请求签名不正确")
        return mapOf("x-pan-client-id" to clientId, "x-pan-tm" to timestamp, "x-pan-token" to token)
    }
    private fun profile(session: OpenSession): OpenProfile = profiles(session.profileId)?.takeIf { it.clientId == session.clientId } ?: throw UserError("开放平台连接配置不可用")
    private suspend fun call(profile: OpenProfile, action: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val url = profile.serviceUrl.toHttpUrl().newBuilder().addPathSegments("v1/$action").build()
        transport.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${profile.serviceToken}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            if (!response.isSuccessful) throw UserError("开放平台连接服务暂时不可用，请检查配置")
            val body = response.body ?: throw UserError("连接服务未返回结果")
            body.source().request(65537)
            if (body.source().buffer.size > 65536) throw UserError("连接服务响应过大")
            val json = runCatching { JSONObject(body.string()) }.getOrElse { throw UserError("连接服务响应格式不正确") }
            if (json.optString("error") == "refresh_not_configured") throw UserError("此连接尚未启用自动刷新，请重新授权", true)
            if (!json.optBoolean("ok")) throw UserError(if (json.optString("error") == "reauthorize") "开放平台账号需重新授权" else "开放平台连接服务未完成请求", json.optString("error") == "reauthorize")
            json.optJSONObject("data") ?: throw UserError("连接服务未返回完整结果")
        }
    }
}
