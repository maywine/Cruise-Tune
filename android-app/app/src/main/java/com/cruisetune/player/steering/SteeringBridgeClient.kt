package com.cruisetune.player.steering

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Binder
import android.os.Process
import kotlinx.coroutines.*

internal class SteeringBridgeClient(
    private val context: Context, private val scope: CoroutineScope,
    private val report: ConnectionReport, private val diagnose: (String) -> Unit,
    private val receive: (SteeringSignal) -> Unit,
) : SteeringConnection {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var enabled = false
    private var generation = 0
    private var binding: ServiceConnection? = null
    private var remote: ISteeringBridge? = null
    private var timer: Job? = null
    private var attempts = 0
    override fun start() { stop(); enabled = true; attempts = 0; bind() }
    override fun stop() { enabled = false; release() }
    private fun release() {
        generation++; remote = null; timer?.cancel(); timer = null
        binding?.let { runCatching { context.unbindService(it) } }; binding = null
    }
    private fun retry() {
        if (!enabled) return
        release(); report(false, "按键连接已断开", "正在重新连接")
        attempts = (attempts + 1).coerceAtMost(4)
        timer = scope.launch { delay(minOf(30_000L, 2_000L shl attempts)); if (enabled) bind() }
    }
    private fun bind() {
        if (!enabled) return
        val run = ++generation
        report(false, "正在启动按键连接", "正在检查车机服务")
        val receiver = object : ISteeringSink.Stub() {
            override fun onConnection(connected: Boolean, state: String, detail: String) {
                if (Binder.getCallingUid() != Process.myUid()) return
                scope.launch { if (enabled && generation == run) {
                    if (connected || !state.startsWith("正在")) timer?.cancel()
                    if (connected) attempts = 0
                    report(connected, state, detail)
                } }
            }
            override fun onKey(code: Int, event: Int, parameter: Int, extra: Int, callerUid: Int, time: Long) {
                if (Binder.getCallingUid() != Process.myUid()) return
                val kind = SteeringEvent.entries.getOrNull(event) ?: return
                scope.launch { if (enabled && generation == run) receive(SteeringSignal(code, kind, parameter, extra, callerUid, time)) }
            }
            override fun onDiagnostic(detail: String) { if (Binder.getCallingUid() != Process.myUid()) return; scope.launch { if (enabled && generation == run) diagnose(detail) } }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!enabled || generation != run) return
                val bridge = ISteeringBridge.Stub.asInterface(service)
                remote = bridge
                scope.launch {
                    try { withContext(io) { bridge.start(receiver) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { if (generation == run) retry() }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { if (generation == run) retry() }
            override fun onBindingDied(name: ComponentName) { if (generation == run) retry() }
            override fun onNullBinding(name: ComponentName) { if (generation == run) retry() }
        }
        binding = connection
        try {
            if (!context.bindService(Intent(context, SteeringBridgeService::class.java), connection, Context.BIND_AUTO_CREATE)) { retry(); return }
            timer = scope.launch { delay(15_000); if (generation == run) retry() }
        } catch (_: Exception) { retry() }
    }
    override suspend fun controlIndex(): Int? {
        val current = remote ?: return null
        val run = generation
        val index = try { withContext(io) { current.controlIndex } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        return index.takeIf { enabled && run == generation && it != SteeringBridgeService.UNKNOWN_MENU }
    }
}
