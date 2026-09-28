package com.cruisetune.player.startup

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.cruisetune.player.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class StartupRestoreState { RESTORED, EMPTY, ACTIVE, DISABLED }

internal data class StartupRestoreReply(
    val state: StartupRestoreState,
    val dashboardEnabled: Boolean = false,
    val detail: String = "",
) {
    fun toBundle() = Bundle().apply {
        putString("state", state.name)
        putBoolean("dashboardEnabled", dashboardEnabled)
        putString("detail", detail)
    }
}

internal interface StartupPlaybackConnection {
    suspend fun restore(forceDashboard: Boolean): StartupRestoreReply
    fun close()
}

/** Binds the existing service without asking it to prepare audio or start playback. */
@androidx.media3.common.util.UnstableApi
internal class StartupMediaConnection(context: Context) : StartupPlaybackConnection {
    private val executor = ContextCompat.getMainExecutor(context)
    private val future = MediaController.Builder(context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()

    override suspend fun restore(forceDashboard: Boolean): StartupRestoreReply {
        val controller = future.awaitResult()
        val result = controller.sendCustomCommand(SessionCommand(PlaybackService.RESTORE_ON_BOOT, Bundle.EMPTY),
            Bundle().apply { putBoolean("forceDashboard", forceDashboard) }).awaitResult()
        check(result.resultCode == SessionResult.RESULT_SUCCESS) { "Background restoration was rejected" }
        return StartupRestoreReply(
            StartupRestoreState.valueOf(checkNotNull(result.extras.getString("state"))),
            result.extras.getBoolean("dashboardEnabled"), result.extras.getString("detail").orEmpty(),
        )
    }

    private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addListener({
            if (continuation.isActive) {
                try { continuation.resume(get()) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }
        }, executor)
    }

    // Also releases a controller whose asynchronous connection completes after cancellation.
    override fun close() { MediaController.releaseFuture(future) }
}

/** A bounded boot window; each resend is checked against the live player by the service. */
internal class StartupRestoration(
    private val connection: StartupPlaybackConnection,
    private val enabled: () -> Boolean,
    private val report: (StartupRestoreReply) -> Unit,
) {
    suspend fun run() {
        try {
            // Initial send, then 5/15/30-second follow-ups. The final 60-second check is not forced.
            // A successful sendBroadcast has no vehicle receipt; early startup deliveries can be lost.
            for ((index, waitMs) in listOf(0L, 5_000L, 10_000L, 15_000L, 30_000L).withIndex()) {
                delay(waitMs)
                if (!enabled()) return
                val reply = withTimeout(10_000L) { connection.restore(forceDashboard = index in 1..3) }
                if (!enabled()) return
                report(reply)
                if (reply.state != StartupRestoreState.RESTORED || !reply.dashboardEnabled) return
            }
        } finally { connection.close() }
    }
}
