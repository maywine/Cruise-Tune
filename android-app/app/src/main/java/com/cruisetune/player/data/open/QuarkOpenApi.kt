package com.cruisetune.player.data.open

import com.cruisetune.player.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class QuarkOpenApi(
    private val accountId: String,
    private val sessions: OpenSessionManager,
    private val broker: OpenBroker,
    client: OkHttpClient,
    private val base: HttpUrl = "https://open-api-drive.quark.cn/".toHttpUrl(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : MusicProvider {
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    override suspend fun listChildren(parentId: String): List<RemoteEntry> {
        val found = linkedMapOf<String, RemoteEntry>()
        val cursors = mutableSetOf<Pair<String, String>>()
        var cursor: JSONObject? = null
        repeat(5000) {
            currentCoroutineContext().ensureActive()
            val body = JSONObject().put("parent_fid", parentId).put("size", 100).put("sort", "updated_at:desc")
            cursor?.let { body.put("query_cursor", it) }
            val (json, _) = request("/open/v1/file/list", body)
            val data = json.optJSONObject("data") ?: throw UserError("开放平台目录结果不完整，已保留原列表")
            val raw = data.opt("file_list")
            val files = if (raw == null || raw == JSONObject.NULL) JSONArray() else raw as? JSONArray ?: throw UserError("开放平台文件列表格式不正确")
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: throw UserError("开放平台文件条目不完整")
                val fid = f.optString("fid").takeIf { it.isNotBlank() } ?: throw UserError("开放平台返回了无效文件标识")
                val name = f.optString("filename", f.optString("file_name")).takeIf { it.isNotBlank() } ?: throw UserError("开放平台返回了无效文件名称")
                val directory = when {
                    f.has("file_type") -> when (f.get("file_type").toString()) { "0" -> true; "1" -> false; else -> throw UserError("开放平台文件类型不明确") }
                    f.has("category") -> f.getInt("category") == 0
                    else -> throw UserError("开放平台文件类型缺失")
                }
                val modified = f.optString("content_hash").takeIf { it.isNotBlank() }?.let { hash -> "hash:$hash" }
                    ?: f.optString("updated_at", f.optString("created_at"))
                found[openFileIdentity(fid)] = RemoteEntry(fid, name, directory, f.optLong("size"), modified)
            }
            if (found.size > 100000) throw UserError("目录过大，请选择更小的音乐目录")
            val last = data.opt("last_page") as? Boolean ?: throw UserError("开放平台未返回有效末页标识")
            if (last) return found.values.toList()
            val next = data.optJSONObject("next_query_cursor") ?: throw UserError("开放平台目录游标缺失")
            val version = next.opt("version") as? String ?: throw UserError("开放平台目录游标版本不正确")
            val token = next.opt("token") as? String ?: throw UserError("开放平台目录游标不正确")
            if (!cursors.add(version to token)) throw UserError("开放平台目录游标重复，已保留原列表")
            cursor = JSONObject().put("version", version).put("token", token)
            val gap = (json.optJSONObject("metadata")?.opt("tq_gap") as? Number)?.toDouble()?.takeIf { n -> n.isFinite() && n >= 0 }?.toLong() ?: 0
            if (gap > 30000) throw UserError("开放平台要求稍后读取，请稍后刷新")
            if (gap > 0) pause(gap)
        }
        throw UserError("目录分页过多，请选择更小的音乐目录")
    }
    override suspend fun resolve(fileId: String): ReadRequest {
        val (json, session) = request("/open/v1/file/get_download_url", JSONObject().put("fid", fileId))
        val data = json.optJSONObject("data") ?: throw UserError("开放平台未返回下载信息")
        val returnedId = data.optString("fid", fileId)
        if (openFileIdentity(returnedId) != openFileIdentity(fileId)) throw UserError("开放平台返回的下载文件不匹配")
        val url = runCatching { data.getString("download_url").toHttpUrl() }.getOrElse { throw UserError("开放平台下载地址不正确") }
        if (url.scheme != "https" || !(url.host == "quark.cn" || url.host.endsWith(".quark.cn"))) throw UserError("开放平台下载域名未在当前支持范围内")
        val cookie = "x_pan_client_id=${session.clientId};x_pan_access_token=${session.accessToken}" + if (session.clientToken.isNotBlank()) ";x_pan_client_token=${session.clientToken}" else ""
        return ReadRequest(url.toString(), mapOf("Cookie" to cookie), session.generation)
    }
    private data class Response(val code: Int, val data: JSONObject?, val newToken: String?, val retryAfter: String?)
    private suspend fun request(path: String, body: JSONObject): Pair<JSONObject, OpenSession> {
        var replayed = false
        var rateRetries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val used = sessions.fresh(accountId)
            val signature = broker.sign(used, "POST", path)
            val url = base.newBuilder().encodedPath(path).addQueryParameter("access_token", used.accessToken).addQueryParameter("device_id", used.deviceId).addQueryParameter("platform", "android").addQueryParameter("req_id", UUID.randomUUID().toString()).build()
            val result = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).apply { signature.forEach { (k, v) -> header(k, v) } }.build()
                transport.newCall(request).execute().use { response ->
                    val body = response.body
                    body?.source()?.request(2097153)
                    if ((body?.source()?.buffer?.size ?: 0) > 2097152) throw UserError("开放平台响应过大，已停止读取")
                    val text = body?.string().orEmpty()
                    Response(response.code, runCatching { JSONObject(text) }.getOrNull(), response.header("x-new-access-token"), response.header("Retry-After"))
                }
            }
            var effective = used
            result.newToken?.takeIf { it.isNotBlank() }?.let { effective = sessions.acceptServerToken(accountId, used.generation, it) }
            val errno = result.data?.optInt("errno")
            if (result.code == 429) {
                if (rateRetries >= 2) throw UserError("开放平台请求较多，请稍后再试", retryable = true)
                rateRetries++
                pause((result.retryAfter?.toLongOrNull()?.coerceIn(0, 30) ?: rateRetries.toLong()) * 1000)
                continue
            }
            if (result.code == 401 || errno == 11001) {
                if (replayed) throw UserError("开放平台账号需重新授权", true)
                replayed = true; sessions.refreshAfterFailure(accountId, used.generation); continue
            }
            if (errno == 11017 && !replayed) { replayed = true; continue }
            if (errno == 11000) throw UserError("开放平台授权已失效，请重新授权", true)
            if (errno == 12003 || errno == 12004) throw UserError("开放平台客户端配置已失效，请检查连接服务")
            if (errno == 23018) {
                val bytes = Regex("^download file size limit\\[(\\d+)\\]$")
                    .matchEntire(result.data?.optString("error_info").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                val limit = bytes?.takeIf { it > 0 }?.let {
                    if (it % 1048576L == 0L) "${it / 1048576L} MiB" else "$it 字节"
                }
                // A provider file-size restriction cannot be repaired by network retries or token rotation.
                throw UserError(if (limit != null) "文件超过夸克当前的下载上限（$limit）" else "夸克限制了该文件下载，请检查网盘下载权限")
            }
            if (result.code == 403) throw UserError("当前授权范围不允许读取该文件或目录")
            if (result.code !in 200..299) throw UserError("开放平台暂时无法读取，请稍后重试", retryable = result.code in listOf(408, 425) || result.code >= 500)
            val json = result.data ?: throw UserError("开放平台响应格式不正确")
            if (json.opt("status") !is Number || json.getInt("status") != 0) throw UserError("开放平台未允许本次读取，请检查授权范围")
            return json to effective
        }
    }
}
