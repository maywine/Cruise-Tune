package com.cruisetune.player.data

import com.cruisetune.player.core.Track
import org.json.JSONObject

object JsonCodec {
    fun encode(t: Track): String = JSONObject().apply {
        put("id", t.id); put("sourceId", t.sourceId); put("fileId", t.fileId)
        put("title", t.title); put("artist", t.artist); put("path", t.relativePath)
        put("size", t.size); put("version", t.version); put("uri", t.localUri)
        put("mime", t.mimeType); put("duration", t.durationMs); put("identity", t.contentIdentity)
    }.toString()

    fun decode(value: String): Track = JSONObject(value).let {
        Track(it.getString("id"), it.getString("sourceId"), it.getString("fileId"), it.getString("title"),
            it.optString("artist"), it.optString("path"), it.optLong("size"), it.optString("version"),
            it.optString("uri"), it.optString("mime", "audio/mpeg"), it.optLong("duration"), it.optString("identity"))
    }
}
