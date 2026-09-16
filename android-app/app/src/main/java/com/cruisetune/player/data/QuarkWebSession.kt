package com.cruisetune.player.data

import com.cruisetune.player.core.MusicProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object QuarkWebSession {
    suspend fun connect(cookie: String, roots: List<String>, files: List<String>,
        provider: (() -> String?, (String) -> Unit) -> MusicProvider, commit: (String) -> Unit) {
        var validated = cookie
        val api = provider({ validated }, { validated = it })
        api.listChildren("0")
        roots.distinct().filter { it != "0" }.forEach { api.listChildren(it) }
        // A different login must be able to read existing files before it can replace their
        // credential binding. This also catches sessions that list folders but cannot download.
        files.distinct().forEach { api.resolve(it) }
        currentCoroutineContext().ensureActive()
        commit(validated)
    }
}
