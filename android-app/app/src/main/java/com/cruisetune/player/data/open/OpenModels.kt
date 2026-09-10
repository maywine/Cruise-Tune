package com.cruisetune.player.data.open

import com.cruisetune.player.core.UserError
import com.cruisetune.player.core.stableHash
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class OpenProfile(val id: String, val clientId: String, val serviceUrl: String, val serviceToken: String) {
    fun json() = JSONObject().put("clientId", clientId).put("serviceUrl", serviceUrl).put("serviceToken", serviceToken).toString()
    companion object {
        const val CALLBACK = "cruisetune://quark-open/callback"
        fun parse(text: String): OpenProfile {
            val j = try { JSONObject(text) } catch (_: Exception) { throw UserError("连接配置格式不正确") }
            if (j.has("signKey") || j.has("clientSecret") || j.has("sign_key") || j.has("client_secret")) throw UserError("应用密钥应保存在授权服务，请导入车机连接配置")
            val client = j.optString("clientId").trim()
            val url = j.optString("serviceUrl").toHttpUrlOrNull()
            val token = j.optString("serviceToken")
            if (!client.matches(Regex("[A-Za-z0-9._-]{1,128}")) || client == "third_party_agent" || url == null || url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || token.length < 24 || token.any { it.code !in 33..126 }) throw UserError("请提供播放器专用的 HTTPS 授权服务配置")
            val normalized = url.toString().trimEnd('/') + "/"
            return OpenProfile(stableHash("quark-open-profile", normalized, client), client, normalized, token)
        }
    }
}

data class OpenSession(
    val accountId: String, val profileId: String, val clientId: String, val userId: String,
    val deviceId: String, val accessToken: String, val refreshToken: String,
    val expiresAtMs: Long, val generation: Long = 0, val clientToken: String = "",
) {
    fun json(): String = JSONObject().apply {
        put("accountId", accountId); put("profileId", profileId); put("clientId", clientId); put("userId", userId)
        put("deviceId", deviceId); put("accessToken", accessToken); put("refreshToken", refreshToken)
        put("expiresAtMs", expiresAtMs); put("generation", generation); put("clientToken", clientToken)
    }.toString()
    companion object {
        fun id(clientId: String, userId: String) = stableHash("quark-open-account", clientId, userId)
        fun parse(text: String): OpenSession = JSONObject(text).let { j ->
            OpenSession(j.getString("accountId"), j.getString("profileId"), j.getString("clientId"), j.getString("userId"),
                j.getString("deviceId"), j.getString("accessToken"), j.getString("refreshToken"), j.getLong("expiresAtMs"), j.optLong("generation"), j.optString("clientToken"))
        }
    }
}

fun openFileIdentity(fid: String): String = fid.substringAfterLast('|').takeIf { it.isNotEmpty() } ?: fid

interface OpenSessionStore {
    fun read(accountId: String): OpenSession?
    fun write(session: OpenSession)
    fun remove(accountId: String)
}

interface OpenBroker {
    suspend fun begin(profile: OpenProfile, state: String): String
    suspend fun exchange(profile: OpenProfile, code: String): OpenSession
    suspend fun rotate(session: OpenSession): OpenSession
    suspend fun sign(session: OpenSession, method: String, path: String): Map<String, String>
}
