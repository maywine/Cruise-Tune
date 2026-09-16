package com.cruisetune.player.data.open

import com.cruisetune.player.core.UserError
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class PendingAuthorization(private val read: () -> String?, private val write: (String) -> Unit, private val remove: () -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    @Synchronized fun create(profileId: String): String {
        val state = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        write(JSONObject().put("state", state).put("createdAt", now()).put("profileId", profileId).toString())
        return state
    }
    @Synchronized fun consume(state: String): String {
        val saved = read()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: throw UserError("授权会话已失效，请重新开始")
        val age = now() - saved.optLong("createdAt")
        if (state.isEmpty() || age !in 0..600000 || !MessageDigest.isEqual(state.toByteArray(), saved.optString("state").toByteArray())) throw UserError("授权会话不匹配或已过期，请重新开始")
        val profileId = saved.optString("profileId").takeIf { it.isNotBlank() } ?: throw UserError("授权配置已失效")
        remove()
        return profileId
    }
}
