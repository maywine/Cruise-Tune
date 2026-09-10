package com.cruisetune.player.data

import com.cruisetune.player.core.UserError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.UUID

data class QrChallenge(val token: String, val url: String)
sealed class QrLoginState {
    data object Waiting : QrLoginState()
    data object Expired : QrLoginState()
    data class Confirmed(val ticket: String) : QrLoginState()
}

/** Kotlin adaptation of the web QR protocol documented by QuarkPan 1.0.5 (MIT).
 * See THIRD_PARTY_NOTICES.md. Tokens and tickets exist only for this in-memory flow.
 */
class QuarkQrAuth(
    client: OkHttpClient,
    private val authBase: HttpUrl = "https://uop.quark.cn/cas/ajax/".toHttpUrl(),
    private val accountUrl: HttpUrl = "https://pan.quark.cn/account/info".toHttpUrl(),
    private val configUrl: HttpUrl = "https://drive.quark.cn/1/clouddrive/config".toHttpUrl(),
) {
    private val cookies = mutableListOf<Cookie>()
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(object : CookieJar {
        @Synchronized override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
            received.forEach { c -> cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; cookies.add(c) }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }).build()
    private fun params() = mapOf("client_id" to "532", "v" to "1.2", "request_id" to UUID.randomUUID().toString())
    suspend fun create(): QrChallenge {
        val data = get(authBase.newBuilder().addPathSegment("getTokenForQrcodeLogin").build(), params())
        if (data.optInt("status") != 2000000) throw UserError("二维码暂时无法获取，请使用网页登录")
        val token = data.optJSONObject("data")?.optJSONObject("members")?.optString("token")?.takeIf { it.isNotBlank() }
            ?: throw UserError("二维码数据不完整，请重新获取")
        val url = "https://su.quark.cn/4_eMHBJ".toHttpUrl().newBuilder()
            .addQueryParameter("token", token).addQueryParameter("client_id", "532").addQueryParameter("ssb", "weblogin")
            .addQueryParameter("uc_param_str", "").addQueryParameter("uc_biz_str", "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0").build()
        return QrChallenge(token, url.toString())
    }
    suspend fun poll(challenge: QrChallenge): QrLoginState {
        val data = get(authBase.newBuilder().addPathSegment("getServiceTicketByQrcodeToken").build(), params() + ("token" to challenge.token))
        val code = data.optInt("status")
        val ticket = data.optJSONObject("data")?.optJSONObject("members")?.optString("service_ticket").orEmpty()
        return when {
            code == 2000000 && ticket.isNotBlank() -> QrLoginState.Confirmed(ticket)
            code in listOf(50004002, 50004003, 50004004) -> QrLoginState.Expired
            code == 50004001 || code == 2000000 -> QrLoginState.Waiting
            else -> throw UserError("二维码登录暂时不可用，请刷新或使用网页登录")
        }
    }
    suspend fun exchange(ticket: String): String {
        require(ticket.isNotBlank())
        get(accountUrl, mapOf("st" to ticket, "lw" to "scan"))
        get(configUrl, mapOf("pr" to "ucpro", "fr" to "pc"))
        val value = cookies.filter { it.domain == "quark.cn" || it.domain.endsWith(".quark.cn") }
            .filter { it.expiresAt > System.currentTimeMillis() }.associate { it.name to it.value }.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (!QuarkApi.validCookie(value)) throw UserError("登录凭证尚未就绪，请使用网页登录")
        return value
    }
    private suspend fun get(base: HttpUrl, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = base.newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        transport.newCall(Request.Builder().url(url).header("User-Agent", QuarkApi.USER_AGENT).header("Referer", QuarkApi.REFERER).header("Accept", "application/json").build()).execute().use { response ->
            if (response.code == 429) throw UserError("请求较多，请稍后刷新二维码")
            if (!response.isSuccessful) throw UserError("二维码服务暂时无法连接，请使用网页登录")
            runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { throw UserError("登录响应格式已变化，请使用网页登录") }
        }
    }
}
