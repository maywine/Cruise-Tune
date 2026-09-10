package com.cruisetune.player.data.open

import android.content.Context
import com.cruisetune.player.core.UserError
import com.cruisetune.player.data.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class OpenConnections(context: Context, private val vault: CredentialVault, apiClient: OkHttpClient, brokerClient: OkHttpClient) {
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val pending = PendingAuthorization({ vault.get("open:pending") }, { vault.put("open:pending", it) }, { vault.remove("open:pending") })
    private val store = object : OpenSessionStore {
        override fun read(accountId: String): OpenSession? = vault.get("open:account:$accountId")?.let { runCatching { OpenSession.parse(it) }.getOrNull() }
        override fun write(session: OpenSession) { vault.put("open:account:${session.accountId}", session.json()) }
        override fun remove(accountId: String) { vault.remove("open:account:$accountId") }
    }
    private val httpBroker = HttpOpenBroker(brokerClient, ::profile)
    val direct: DirectQuarkAuth by lazy {
        val config = try {
            org.json.JSONObject(context.assets.open("quark_cli_client.json").bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            throw UserError("此安装包尚未配置夸克扫码授权")
        }
        val clientId = config.optString("clientId").trim()
        val signKey = config.optString("signKey").trim()
        if (clientId.isBlank() || signKey.isBlank()) throw UserError("此安装包尚未配置夸克扫码授权")
        val device = preferences.getString("quarkInstallId", null) ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString("quarkInstallId", it).commit()) { "设备信息保存失败" }
        }
        DirectQuarkAuth(apiClient, clientId, signKey, device)
    }
    val broker = object : OpenBroker {
        override suspend fun begin(profile: OpenProfile, state: String) = httpBroker.begin(profile, state)
        override suspend fun exchange(profile: OpenProfile, code: String) = httpBroker.exchange(profile, code)
        override suspend fun rotate(session: OpenSession) = if (session.profileId == DirectQuarkAuth.PROFILE) direct.rotate(session) else httpBroker.rotate(session)
        override suspend fun sign(session: OpenSession, method: String, path: String) = if (session.profileId == DirectQuarkAuth.PROFILE) direct.sign(session, method, path) else httpBroker.sign(session, method, path)
    }
    suspend fun completeDirect(challenge: DirectQuarkAuth.Challenge, code: String): OpenSession = withContext(Dispatchers.IO) {
        val session = sessions.activate(direct.complete(challenge, code))
        check(preferences.edit().putString("quarkDirectAccount", session.accountId).commit()) { "账号入口保存失败" }
        session
    }
    val sessions = OpenSessionManager(store, broker)
    private val apiTransport = apiClient
    val configured: Boolean get() = preferences.contains("quarkOpenProfile")
    fun profile(id: String): OpenProfile? = vault.get("open:profile:$id")?.let { runCatching { OpenProfile.parse(it) }.getOrNull() }
    fun provider(accountId: String) = QuarkOpenApi(accountId, sessions, broker, apiTransport)
    suspend fun importProfile(text: String) = withContext(Dispatchers.IO) {
        val config = OpenProfile.parse(text)
        vault.put("open:profile:${config.id}", config.json())
        if (!preferences.edit().putString("quarkOpenProfile", config.id).commit()) throw UserError("连接配置未能保存")
    }
    suspend fun begin(): String = withContext(Dispatchers.IO) {
        val config = preferences.getString("quarkOpenProfile", null)?.let(::profile) ?: throw UserError("请先导入开放平台连接配置")
        val state = pending.create(config.id)
        broker.begin(config, state)
    }
    suspend fun complete(code: String, state: String): OpenSession = withContext(Dispatchers.IO) {
        if (code.isBlank() || state.isBlank()) throw UserError("授权回调不完整，请重新开始")
        val config = profile(pending.consume(state)) ?: throw UserError("连接配置已不可用")
        val session = sessions.activate(broker.exchange(config, code))
        if (!preferences.edit().putString("quarkOpenAccount", session.accountId).commit()) throw UserError("账号入口未能保存，请重新授权")
        session
    }
}
