package com.cruisetune.player

import android.app.Application
import com.cruisetune.player.data.*
import com.cruisetune.player.playback.MediaCache
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CruiseApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { LibraryDatabase(this) }
    val vault by lazy { CredentialVault(this) }
    val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val trusted = listOf("quark.cn", "uc.cn", "ucweb.com").any { host == it || host.endsWith(".$it") }
            chain.proceed(if (trusted) request else request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build())
        }.build() }
    val streamHttp by lazy { http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).addNetworkInterceptor(com.cruisetune.player.playback.RangeValidationInterceptor()).build() }
    private val brokerHttp by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
    val openConnections by lazy { com.cruisetune.player.data.open.OpenConnections(this, vault, http, brokerHttp) }
    val library by lazy { LibraryRepository(this, database, vault, http, openConnections) }
    val preferences by lazy { getSharedPreferences("preferences", MODE_PRIVATE) }
    val media by lazy { MediaCache(this) }
    override fun onCreate() {
        super.onCreate()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(if (preferences.getBoolean("dayMode", false)) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        scope.launch { library.reload() }
    }
}
