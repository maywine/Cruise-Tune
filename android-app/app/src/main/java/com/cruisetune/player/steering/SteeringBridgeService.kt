package com.cruisetune.player.steering

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.*

/** Owns the vendor callback in a disposable, app-private process. No invented unregister IPC. */
class SteeringBridgeService : Service() {
    companion object {
        internal const val UNKNOWN_MENU = Int.MIN_VALUE
        fun isBridgeProcess(packageName: String): Boolean {
            val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
                else runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrNull()
            return name == "$packageName:steering"
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sink: ISteeringSink? = null
    private var connection: OneOsConnection? = null
    private var receiverDeath: Pair<IBinder, IBinder.DeathRecipient>? = null
    private var closed = false
    private fun deliver(action: (ISteeringSink) -> Unit) {
        val current = sink ?: return
        try { action(current) } catch (_: android.os.RemoteException) { scope.launch { shutdown() } }
    }
    private fun requireOwnCaller() { check(Binder.getCallingUid() == Process.myUid()) { "Private steering bridge" } }
    private val binder = object : ISteeringBridge.Stub() {
        override fun start(receiver: ISteeringSink) {
            requireOwnCaller()
            scope.launch {
                if (closed || sink != null) return@launch
                sink = receiver
                val death = IBinder.DeathRecipient { scope.launch { shutdown() } }
                try { receiver.asBinder().linkToDeath(death, 0) }
                catch (_: android.os.RemoteException) { shutdown(); return@launch }
                receiverDeath = receiver.asBinder() to death
                connection = OneOsConnection(this@SteeringBridgeService, scope,
                    { ready, state, detail -> deliver { it.onConnection(ready, state, detail) } },
                    { detail -> deliver { it.onDiagnostic(detail) } }, restartOwner = { shutdown() }) { signal ->
                    deliver { it.onKey(signal.keyCode, signal.event.ordinal, signal.parameter, signal.extra, signal.callerUid, signal.receivedAt) }
                }.also { it.start() }
            }
        }
        override fun getControlIndex(): Int {
            requireOwnCaller()
            // This runs on our Binder pool, never on either process's UI thread.
            return runBlocking { withContext(Dispatchers.Main.immediate) { connection?.controlIndex() ?: UNKNOWN_MENU } }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return false }
    override fun onDestroy() { shutdown(); super.onDestroy() }
    private fun shutdown() {
        if (closed) return
        closed = true
        connection?.stop(); connection = null
        receiverDeath?.let { (remote, death) -> runCatching { remote.unlinkToDeath(death, 0) } }; receiverDeath = null
        sink = null; scope.cancel()
        // Killing only this dedicated process releases all outstanding callback Binder objects,
        // including those a vendor server may still retain after unbindService.
        if (isBridgeProcess(packageName)) Process.killProcess(Process.myPid())
    }
}
