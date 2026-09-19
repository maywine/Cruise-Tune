package com.cruisetune.player.dashboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

internal enum class DashboardEndpointKind { READY, MISSING, ACTION_MISMATCH, DISABLED, NOT_EXPORTED, NOT_SYSTEM, PERMISSION_DENIED }

internal data class DashboardEndpoint(
    val kind: DashboardEndpointKind,
    val detail: String,
    val version: String? = null,
    val uid: Int? = null,
    val systemApp: Boolean? = null,
) {
    val ready: Boolean get() = kind == DashboardEndpointKind.READY
}

internal sealed class DashboardDispatch {
    object Dispatched : DashboardDispatch()
    data class Failed(val detail: String, val retryable: Boolean) : DashboardDispatch()
}

internal interface DashboardTransport {
    fun inspect(): DashboardEndpoint
    fun dispatch(intent: Intent): DashboardDispatch
}

/** The only production endpoint discovered in the reviewed sample. */
internal class EcarxBroadcastTransport(private val context: Context) : DashboardTransport {
    companion object {
        const val ACTION = "ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER"
        const val TARGET_PACKAGE = "com.geely.online.service"
        const val TARGET_RECEIVER = "com.media.control.MediaControlReceiver"
        val component: ComponentName get() = ComponentName(TARGET_PACKAGE, TARGET_RECEIVER)
    }

    override fun inspect(): DashboardEndpoint {
        val manager = context.packageManager
        val receiver = try { manager.getReceiverInfo(component, 0) }
        catch (_: PackageManager.NameNotFoundException) {
            return DashboardEndpoint(DashboardEndpointKind.MISSING, "未找到兼容的原车媒体服务")
        }
        val application = try { manager.getApplicationInfo(TARGET_PACKAGE, 0) }
        catch (_: PackageManager.NameNotFoundException) {
            return DashboardEndpoint(DashboardEndpointKind.MISSING, "未找到兼容的原车媒体服务")
        }
        val version = runCatching { manager.getPackageInfo(TARGET_PACKAGE, 0).versionName }.getOrNull()
        val system = application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val matchingReceivers = manager.queryBroadcastReceivers(
            Intent(ACTION).setComponent(component), PackageManager.GET_RESOLVED_FILTER,
        )
        if (matchingReceivers.none { it.activityInfo?.name == component.className }) {
            return DashboardEndpoint(DashboardEndpointKind.ACTION_MISMATCH, "原车媒体服务未声明兼容的广播 action", version, application.uid, system)
        }
        if (!application.enabled || !receiver.enabled) return DashboardEndpoint(DashboardEndpointKind.DISABLED, "原车媒体服务已停用", version, application.uid, system)
        if (!receiver.exported) return DashboardEndpoint(DashboardEndpointKind.NOT_EXPORTED, "原车媒体服务不允许外部调用", version, application.uid, system)
        if (!system) return DashboardEndpoint(DashboardEndpointKind.NOT_SYSTEM, "目标媒体服务不是已验证的系统应用", version, application.uid, system)
        val permission = receiver.permission
        if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            return DashboardEndpoint(DashboardEndpointKind.PERMISSION_DENIED, "缺少原车媒体服务要求的权限", version, application.uid, system)
        }
        return DashboardEndpoint(DashboardEndpointKind.READY, "已找到原车媒体服务，播放时将发送信息", version, application.uid, system)
    }

    override fun dispatch(intent: Intent): DashboardDispatch = try {
        context.sendBroadcast(intent)
        DashboardDispatch.Dispatched
    } catch (_: SecurityException) {
        DashboardDispatch.Failed("原车媒体服务拒绝广播", retryable = false)
    } catch (_: RuntimeException) {
        DashboardDispatch.Failed("发送仪表媒体信息时发生系统异常", retryable = true)
    }
}
