package com.cruisetune.player.steering

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*

/** Lifecycle belongs to PlaybackService, never to the settings window. All remote calls run off main. */
internal class OneOsConnection(
    private val context: Context, private val scope: CoroutineScope,
    private val report: (Boolean, String, String) -> Unit,
    private val loadContract: (Context) -> OneOsContract = OneOsContract::load,
    private val receive: (Int, SteeringEvent, Long) -> Unit,
) {
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var generation = 0
    private var enabled = false
    private var attempts = 0
    private var binding: ServiceConnection? = null
    private var job: Job? = null
    private var timer: Job? = null
    private var listener: OneOsListener? = null
    private var subscription: OneOsSubscription? = null
    private var death: Pair<IBinder, IBinder.DeathRecipient>? = null
    fun start() { stop(); enabled = true; attempts = 0; bind() }
    fun stop() { enabled = false; release() }
    suspend fun controlIndex(): Int? {
        val current = subscription ?: return null
        val run = generation
        val result = try { withContext(io) { current.controlIndex() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        return result.takeIf { enabled && generation == run && subscription === current }
    }
    private fun release() {
        generation++
        timer?.cancel(); timer = null
        listener?.close(); listener = null
        job?.cancel(); job = null
        death?.let { (binder, recipient) -> runCatching { binder.unlinkToDeath(recipient, 0) } }; death = null
        subscription?.let { old -> scope.launch(NonCancellable + io) { runCatching { old.close() } } }; subscription = null
        binding?.let { runCatching { context.unbindService(it) } }; binding = null
    }
    private fun failed(message: String, detail: String, retry: Boolean) {
        if (!enabled) return
        release()
        report(false, message, detail)
        if (retry) {
            attempts = (attempts + 1).coerceAtMost(4)
            timer = scope.launch { delay(minOf(30_000L, 2_000L shl attempts)); if (enabled) bind() }
        }
    }
    private fun bind() {
        if (!enabled) return
        val component = ComponentName(OneOsDiscovery.PACKAGE, "${OneOsDiscovery.PACKAGE}.OneOSApiService")
        val info = try { context.packageManager.getServiceInfo(component, 0) }
        catch (_: PackageManager.NameNotFoundException) {
            failed("此设备没有 OneOS 按键服务", "标准媒体按键仍可使用；模拟器无法验证原车按键", false); return
        }
        if (info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
            failed("按键服务身份不匹配", "仅连接车机预装的系统服务", false); return
        }
        report(false, "正在连接车机按键", "等待 OneOS 服务")
        val run = ++generation
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (run != generation || !enabled) return
                timer?.cancel()
                job = scope.launch {
                    var acquired: OneOsSubscription? = null
                    var acquiredDeath: Pair<IBinder, IBinder.DeathRecipient>? = null
                    try {
                        withContext(io) {
                            val contract = loadContract(context)
                            val input = OneOsDiscovery.input(service)
                            val callback = OneOsListener(contract.callbacks, setOf(info.applicationInfo.uid, android.os.Process.SYSTEM_UID)) { code, event ->
                                val time = SystemClock.uptimeMillis()
                                scope.launch(Dispatchers.Main.immediate) { if (enabled && run == generation) receive(code, event, time) }
                            }
                            withContext(Dispatchers.Main.immediate) { if (run == generation) listener = callback else callback.close() }
                            ensureActive()
                            acquired = contract.register(input, callback, context.packageName + ".Steering")
                            val recipient = IBinder.DeathRecipient {
                                scope.launch(Dispatchers.Main.immediate) {
                                    if (enabled && run == generation) failed("车机按键连接中断", "正在自动重连", true)
                                }
                            }
                            input.linkToDeath(recipient, 0)
                            acquiredDeath = input to recipient
                            withContext(Dispatchers.Main.immediate) {
                                if (run == generation) death = acquiredDeath
                                else input.unlinkToDeath(recipient, 0)
                            }
                        }
                        if (run == generation && enabled) {
                            subscription = acquired
                            attempts = 0
                            report(true, "已连接，等待按键", "监听已注册；按下方向盘按键可查看检测结果")
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (run == generation) when (e) {
                            is SecurityException -> failed("车机未授予按键访问权限", "需要实车确认权限，标准媒体按键仍可使用", false)
                            is UnsupportedOneOs -> failed("此车机的按键接口暂不兼容", e.message ?: "需要实车确认接口", false)
                            else -> failed("暂时无法连接车机按键", "正在自动重连（${e.javaClass.simpleName}）", true)
                        }
                    } finally {
                        if (acquiredDeath !== death) acquiredDeath?.let { (binder, recipient) -> runCatching { binder.unlinkToDeath(recipient, 0) } }
                        if (acquired !== subscription) withContext(NonCancellable + io) { runCatching { acquired?.close() } }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { if (run == generation) failed("车机按键连接中断", "正在自动重连", true) }
            override fun onBindingDied(name: ComponentName) { if (run == generation) failed("车机按键服务已重启", "正在自动重连", true) }
            override fun onNullBinding(name: ComponentName) { if (run == generation) failed("车机未开放按键服务", "标准媒体按键仍可使用", false) }
        }
        binding = connection
        try {
            if (!context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)) {
                failed("暂时无法连接车机按键", "正在自动重连", true); return
            }
            timer = scope.launch { delay(10_000); if (run == generation) failed("连接车机按键超时", "正在自动重连", true) }
        } catch (_: SecurityException) {
            failed("车机未授予按键访问权限", "需要实车确认服务权限", false)
        }
    }
}
