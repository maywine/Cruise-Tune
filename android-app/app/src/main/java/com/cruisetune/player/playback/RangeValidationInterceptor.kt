package com.cruisetune.player.playback

import com.cruisetune.player.core.InvalidMediaRange
import okhttp3.Interceptor
import okhttp3.Response

/** 206 must identify the requested bytes. A 200 fallback is left to Media3's safe skip logic. */
class RangeValidationInterceptor : Interceptor {
    companion object { const val SEQUENTIAL_HEADER = "X-Cruise-Sequential-Read" }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val range = request.header("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val response = chain.proceed(request)
        if (range == null) return response
        val start = range.groupValues[1].toLongOrNull() ?: return response
        val requestedEnd = range.groupValues[2].toLongOrNull()
        if (response.code == 200) return if (start > 0) response.newBuilder().header(SEQUENTIAL_HEADER, "true").build() else response
        if (response.code != 206) return response
        val returned = response.header("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).matchEntire(it.trim()) }
        val actualStart = returned?.groupValues?.get(1)?.toLongOrNull()
        val end = returned?.groupValues?.get(2)?.toLongOrNull()
        val total = returned?.groupValues?.get(3)?.toLongOrNull()
        val length = response.body?.contentLength() ?: -1
        if (actualStart != start || end == null || end < start || (requestedEnd != null && end > requestedEnd) || (total != null && total <= end) || (length >= 0 && length != end - start + 1)) {
            response.close()
            throw InvalidMediaRange()
        }
        return response
    }
}
