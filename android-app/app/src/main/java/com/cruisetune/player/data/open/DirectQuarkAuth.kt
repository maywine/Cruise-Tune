package com.cruisetune.player.data.open

import com.cruisetune.player.core.UserError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Agent-page protocol verified by the isolated Python login experiment. */
class DirectQuarkAuth(client: OkHttpClient, private val clientId: String, private val signKey: String,
    private val installId: String, private val base: HttpUrl = "https://open-api-drive.quark.cn/".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis) : OpenBroker {
    data class Challenge(val url: String, val pageCode: String, val deviceId: String)
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    companion object { const val PROFILE = "quark-direct-v1"; private const val AGENT = "cruise-tune" }
    private fun identity() = JSONObject().put("client_device_id", installId).put("device_name", "Cruise Tune Android")
        .put("agent_id", AGENT).put("work_dir", "cruise-tune")
    suspend fun create(): Challenge {
        val data = call("POST", "/agent/v1/get_authorize_page_url", body = identity().put("client_id", clientId)
            .put("is_cloud_agent", "false").put("is_unsure_agent", "true"))
        val raw = data.optString("authorize_page_url").toHttpUrl()
        if (raw.scheme != "https" || raw.host != "pan.quark.cn" || raw.port != 443 || raw.username.isNotEmpty() || raw.password.isNotEmpty() || raw.encodedPath != "/open/v1/oauth/agent") throw UserError("授权地址不正确，请稍后重试")
        val page = required(data, "page_code")
        if (raw.queryParameter("page_code") != page) throw UserError("授权页面不匹配，请重新开始")
        val device = required(data, "device_id")
        val url = raw.newBuilder().setQueryParameter("client_id", clientId).setQueryParameter("scope", "clouddrive.base`clouddrive.netdisk")
            .setQueryParameter("redirect_uri", "").setQueryParameter("device_id", device).setQueryParameter("client_device_id", installId)
            .setQueryParameter("device_name", "Cruise Tune Android").setQueryParameter("agent_id", AGENT).build()
        return Challenge(url.toString(), page, device)
    }
    suspend fun poll(challenge: Challenge): String? {
        val d = call("GET", "/agent/v1/oauth/get_aac_by_pagecode", mapOf("page_code" to challenge.pageCode))
        return when (d.optString("status")) {
            "success" -> required(d, "agent_auth_code")
            "expired", "cancelled", "denied" -> throw UserError("二维码已失效，请刷新后扫码")
            else -> null
        }
    }
    suspend fun complete(challenge: Challenge, code: String): OpenSession {
        if (!code.matches(Regex("AAC-[A-Za-z0-9-]{16,256}"))) throw UserError("授权码格式不正确")
        val identity = identity()
        val query = identity.keys().asSequence().associateWith { identity.getString(it) } + ("agent_auth_code" to code)
        val d = call("GET", "/agent/v1/oauth/agent_auth_code", query)
        if (d.optString("status") !in listOf("confirmed", "install_confirmed")) throw UserError("授权尚未完成或已过期，请重新扫码", true)
        val user = required(d, "user_id")
        val device = required(d, "device_id")
        if (device != challenge.deviceId) throw UserError("授权设备不匹配，请重新扫码", true)
        return OpenSession(OpenSession.id(clientId, user), PROFILE, clientId, user, device,
            required(d, "access_token"), required(d, "refresh_token"), expiry(d))
    }
    override suspend fun rotate(session: OpenSession): OpenSession {
        val d = call("POST", "/agent/v1/oauth/access_token/rotate", body = JSONObject().put("refresh_token", session.refreshToken).put("device_id", session.deviceId))
        return session.copy(accessToken = required(d, "access_token"), refreshToken = required(d, "refresh_token"), expiresAtMs = expiry(d))
    }
    override suspend fun sign(session: OpenSession, method: String, path: String): Map<String, String> = headers(method, path)
    private fun headers(method: String, path: String): Map<String, String> {
        val timestamp = now().toString()
        val token = MessageDigest.getInstance("SHA-256").digest("${method.uppercase()}&$path&$timestamp&$signKey".toByteArray()).joinToString("") { "%02x".format(it) }
        return mapOf("x-pan-client-id" to clientId, "x-pan-tm" to timestamp, "x-pan-token" to token)
    }
    private suspend fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val url = base.newBuilder().encodedPath(path).addQueryParameter("req_id", UUID.randomUUID().toString()).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).apply { headers(method, path).forEach { (k,v) -> header(k,v) }; header("Accept", "application/json")
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }.build()
        transport.newCall(request).execute().use { response ->
            if (response.code == 401) throw UserError("夸克授权已失效，请重新扫码", true)
            if (!response.isSuccessful) throw UserError("夸克暂时无法连接，请稍后重试", retryable = response.code in listOf(408, 425, 429) || response.code >= 500)
            val content = response.body ?: throw UserError("夸克未返回授权信息")
            content.source().request(1048577)
            if (content.source().buffer.size > 1048576) throw UserError("授权响应过大")
            val j = try { JSONObject(content.string()) } catch (_: Exception) { throw UserError("授权响应格式不正确") }
            if (j.opt("status") !is Number || j.getInt("status") != 0) throw UserError("夸克未完成本次授权请求，请重新扫码", j.optInt("errno") in listOf(11000,11001))
            j.optJSONObject("data") ?: throw UserError("夸克授权信息不完整")
        }
    }
    private fun required(j: JSONObject, name: String) = (j.opt(name) as? String)?.takeIf { it.isNotBlank() } ?: throw UserError("夸克授权信息不完整", true)
    private fun expiry(j: JSONObject): Long {
        val absolute = (j.opt("access_token_expires_at") as? Number)?.toLong() ?: 0
        if (absolute > 0) return if (absolute < 1_000_000_000_000) absolute * 1000 else absolute
        val seconds = (j.opt("expires_in") as? Number)?.toLong() ?: 0
        return if (seconds in 1..31536000) now() + seconds * 1000 else 0
    }
    override suspend fun begin(profile: OpenProfile, state: String): String = throw UnsupportedOperationException()
    override suspend fun exchange(profile: OpenProfile, code: String): OpenSession = throw UnsupportedOperationException()
}
