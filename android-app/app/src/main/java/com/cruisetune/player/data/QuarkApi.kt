package com.cruisetune.player.data

import com.cruisetune.player.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Read-only adapter for the user's own Quark web session. No borrowed app keys. */
class QuarkApi(
    private val client: OkHttpClient,
    private val readCookie: () -> String?,
    private val saveCookie: (String) -> Unit,
    private val base: HttpUrl = "https://drive-pc.quark.cn/1/clouddrive/".toHttpUrl(),
) : MusicProvider {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val PC_CLIENT_VERSION = "2.5.56"
        const val PC_DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/$PC_CLIENT_VERSION Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch"
        const val REFERER = "https://pan.quark.cn/"
        fun validCookie(cookie: String) = cookie.length in 10..32768 && !cookie.contains('\n') && !cookie.contains('\r') &&
            cookie.split(';').any { it.trim().startsWith("__puus=") || it.trim().startsWith("__pus=") || it.trim().startsWith("__kps=") }
        fun mergeCookies(existing: String, updates: List<Pair<String, String>>): String {
            val values = linkedMapOf<String, String>()
            existing.split(';').forEach { part ->
                val key = part.substringBefore('=').trim()
                if (key.isNotEmpty() && part.contains('=')) values[key] = part.substringAfter('=').trim()
            }
            updates.forEach { (key, value) -> if (key.startsWith("__")) values[key] = value }
            return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    override suspend fun listChildren(parentId: String): List<RemoteEntry> {
        val result = linkedMapOf<String, RemoteEntry>()
        for (page in 1..1000) {
            currentCoroutineContext().ensureActive()
            val json = request("file/sort", mapOf("pdir_fid" to parentId, "_page" to page.toString(), "_size" to "100", "_fetch_total" to "1", "fetch_all_file" to "1", "fetch_risk_file_name" to "1", "_sort" to "file_type:asc,file_name:asc"))
            val list = json.optJSONObject("data")?.optJSONArray("list") ?: throw UserError("夸克目录数据不完整，请重新刷新")
            val before = result.size
            for (i in 0 until list.length()) {
                val file = list.getJSONObject(i)
                val id = file.optString("fid").ifBlank { throw UserError("夸克返回了无效文件") }
                val name = file.optString("file_name").ifBlank { "未命名文件" }
                val directory = when {
                    file.has("file") -> !file.getBoolean("file")
                    file.has("dir") -> file.getBoolean("dir")
                    file.has("file_type") -> file.getInt("file_type") == 0
                    else -> throw UserError("夸克文件类型不完整，已保留原列表")
                }
                result[id] = RemoteEntry(id, name, directory, file.optLong("size"), file.optString("updated_at", file.optString("created_at")))
            }
            val total = json.optJSONObject("metadata")?.optLong("_total", -1)?.takeIf { it >= 0 }
                ?: json.optJSONObject("metadata")?.optLong("total", -1) ?: -1
            if (list.length() > 0 && result.size == before) throw UserError("夸克分页未继续，已保留原列表，请稍后重试")
            if (list.length() == 0 || (total >= 0 && page * 100L >= total) || (total < 0 && list.length() < 100)) {
                if (total > result.size) throw UserError("夸克目录读取不完整，已保留原列表，请重新刷新")
                return result.values.toList()
            }
        }
        throw UserError("目录文件过多，请选择更小的音乐目录")
    }

    override suspend fun resolve(fileId: String): ReadRequest {
        // Verified with a web-authorized FLAC above 50 MiB. Playback still reads only
        // requested byte ranges; this does not change the OAuth/Token provider.
        val json = request("file/download", query = mapOf("sys" to "win32", "ve" to PC_CLIENT_VERSION, "ut" to "", "guid" to ""),
            body = JSONObject().put("fids", JSONArray().put(fileId)), userAgent = PC_DOWNLOAD_USER_AGENT)
        val data = json.optJSONArray("data") ?: throw UserError("暂时无法获取夸克文件地址")
        val match = (0 until data.length()).map { data.getJSONObject(it) }.firstOrNull { it.optString("fid", fileId) == fileId }
            ?: throw UserError("夸克文件已不可用")
        val url = match.optString("download_url").ifEmpty { throw UserError("夸克尚未提供可用下载地址") }.toHttpUrl()
        val trusted = listOf("quark.cn", "uc.cn", "ucweb.com").any { url.host == it || url.host.endsWith(".$it") }
        if (url.scheme != "https" || !trusted) throw UserError("夸克下载域名已变化，需要更新接入适配")
        return ReadRequest(url.toString(), mapOf("Cookie" to cookie(), "Referer" to REFERER, "User-Agent" to PC_DOWNLOAD_USER_AGENT))
    }

    private fun cookie(): String = readCookie()?.takeIf { validCookie(it) } ?: throw UserError("夸克账号需重新连接", true)

    private suspend fun request(path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null, userAgent: String = USER_AGENT): JSONObject = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments(path).addQueryParameter("pr", "ucpro").addQueryParameter("fr", "pc").apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val builder = Request.Builder().url(url).header("Cookie", cookie()).header("Referer", REFERER)
            .header("User-Agent", userAgent).header("Accept", "application/json")
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        for (attempt in 0..2) {
            currentCoroutineContext().ensureActive()
            var waitMs = (attempt + 1) * 1000L
            val result = transport.newCall(builder.header("Cookie", cookie()).build()).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw UserError("夸克账号需重新连接", true)
                if (response.code == 429) {
                    if (attempt == 2) throw UserError("夸克请求较多，请稍后再试", retryable = true)
                    val retry = response.header("Retry-After")
                    waitMs = retry?.toLongOrNull()?.let { it.coerceIn(0, 30) * 1000 }
                        ?: retry?.let { value -> runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(value)!!.time - System.currentTimeMillis() }.getOrNull() }?.coerceIn(0, 30000)
                        ?: waitMs
                    return@use null
                }
                if (!response.isSuccessful) {
                    val code = response.peekBody(8192).use { peek -> runCatching { JSONObject(peek.string()).optInt("code") }.getOrNull() }
                    if (code == 23018) throw UserError("夸克限制了该文件下载，请检查网盘下载权限")
                    throw UserError("夸克暂时无法连接，请稍后重试", retryable = response.code in listOf(408, 425) || response.code >= 500)
                }
                val updates = Cookie.parseAll(url, response.headers).map { it.name to it.value }
                if (updates.isNotEmpty()) saveCookie(mergeCookies(cookie(), updates))
                val raw = response.body?.string() ?: throw UserError("夸克返回了空数据")
                val json = runCatching { JSONObject(raw) }.getOrElse { throw UserError("夸克响应格式已变化，请稍后重试") }
                val status = json.optInt("status", 200)
                if (status == 401 || status == 403) throw UserError("夸克账号需重新连接", true)
                if (json.optInt("code", 0) == 23018) throw UserError("夸克限制了该文件下载，请检查网盘下载权限")
                if (json.optInt("code", 0) != 0 || status >= 400) throw UserError("夸克未允许本次读取，请检查账号或文件权限")
                json
            }
            if (result != null) return@withContext result
            delay(waitMs)
        }
        throw UserError("夸克暂时无法连接")
    }
}
