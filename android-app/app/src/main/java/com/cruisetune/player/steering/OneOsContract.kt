package com.cruisetune.player.steering

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import dalvik.system.PathClassLoader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

internal class UnsupportedOneOs(message: String) : Exception(message)

internal interface OneOsSubscription : AutoCloseable { fun controlIndex(): Int }

/** The only hand-written outbound transaction is the recovered read-only IServiceManager.getService. */
internal object OneOsDiscovery {
    const val PACKAGE = "com.geely.service.oneosapi"
    const val ROOT = "com.geely.lib.oneosapi.IServiceManager"
    const val INPUT = "com.geely.lib.oneosapi.input.IInputManager"
    const val LISTENER = "com.geely.lib.oneosapi.input.IInputListener"
    // Service IDs seen in the reference client, not transaction numbers or confirmed input IDs.
    private val candidates = intArrayOf(8, 12, 16, 20, 24, 28, 32, 36, 40)
    fun input(root: IBinder): IBinder {
        if (root.interfaceDescriptor == INPUT) return root
        if (root.interfaceDescriptor != ROOT) throw UnsupportedOneOs("OneOS 主服务接口不匹配")
        for (id in candidates) {
            val request = Parcel.obtain(); val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(ROOT); request.writeInt(id)
                if (!root.transact(2, request, response, 0)) throw UnsupportedOneOs("OneOS 不支持读取服务")
                response.readException()
                val binder = response.readStrongBinder() ?: continue
                if (binder.interfaceDescriptor == INPUT) return binder
            } finally { response.recycle(); request.recycle() }
        }
        throw UnsupportedOneOs("未找到匹配的输入服务；需要实车确认服务编号")
    }
}

/** Read the installed system SDK's named methods; never guess register/unregister transaction numbers. */
internal class OneOsContract private constructor(
    private val inputStub: Class<*>, private val listenerType: Class<*>,
    private val register: Method, private val unregister: Method, private val controlIndex: Method,
    val callbacks: Map<Int, SteeringEvent>,
) {
    companion object {
        fun load(context: Context): OneOsContract {
            val paths = listOf(OneOsDiscovery.PACKAGE, "com.geely.mediacenterservice").mapNotNull { name ->
                val info = try { context.packageManager.getApplicationInfo(name, 0) }
                    catch (_: android.content.pm.PackageManager.NameNotFoundException) { return@mapNotNull null }
                if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return@mapNotNull null
                listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
            }.flatten()
            if (paths.isEmpty()) throw UnsupportedOneOs("未找到车机系统 SDK")
            val loader = PathClassLoader(paths.joinToString(java.io.File.pathSeparator), context.classLoader)
            try {
                return fromTypes(loader.loadClass(OneOsDiscovery.INPUT), loader.loadClass(OneOsDiscovery.INPUT + "\$Stub"),
                    loader.loadClass(OneOsDiscovery.LISTENER), loader.loadClass(OneOsDiscovery.LISTENER + "\$Stub"))
            } catch (e: ReflectiveOperationException) {
                throw UnsupportedOneOs("车机 SDK 的按键接口暂不兼容（${e.javaClass.simpleName}）")
            } catch (_: LinkageError) { throw UnsupportedOneOs("车机 SDK 无法加载") }
        }
        internal fun fromTypes(input: Class<*>, inputStub: Class<*>, listener: Class<*>, listenerStub: Class<*>): OneOsContract {
            val register = input.getMethod("registerKeyListener", listener, String::class.java, IntArray::class.java)
            val unregister = input.getMethod("unregisterKeyListener", String::class.java)
            val controlIndex = input.getMethod("getControlIndex")
            if (controlIndex.returnType != Integer.TYPE) throw UnsupportedOneOs("无法核验当前按键菜单接口")
            if (!IInterface::class.java.isAssignableFrom(listener) || !listener.isInterface ||
                listOf(register, unregister).any { it.returnType != Void.TYPE && it.returnType != Boolean::class.javaPrimitiveType }) {
                throw UnsupportedOneOs("按键监听方法签名不匹配")
            }
            val events = linkedMapOf(
                "onKeyCodeEvent" to SteeringEvent.RAW, "onShortClick" to SteeringEvent.SHORT,
                "onHoldingPressStarted" to SteeringEvent.HOLD_START, "onHoldingPressStopped" to SteeringEvent.HOLD_END,
                "onLongPressTriggered" to SteeringEvent.LONG, "onDoubleClick" to SteeringEvent.NATIVE_DOUBLE)
            val callbacks = events.map { (name, event) ->
                val arguments = Array(if (event == SteeringEvent.RAW) 3 else 2) { Integer.TYPE }
                if (listener.getMethod(name, *arguments).returnType != Void.TYPE) throw UnsupportedOneOs("按键回调签名不匹配")
                val field = listenerStub.getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }
                field.getInt(null) to event
            }.toMap()
            if (callbacks.size != events.size || callbacks.keys.any { it !in 1..IBinder.LAST_CALL_TRANSACTION })
                throw UnsupportedOneOs("按键回调编号不匹配")
            inputStub.getMethod("asInterface", IBinder::class.java)
            return OneOsContract(inputStub, listener, register, unregister, controlIndex, callbacks)
        }
    }
    fun register(input: IBinder, callback: OneOsListener, tag: String): OneOsSubscription {
        if (input.interfaceDescriptor != OneOsDiscovery.INPUT) throw UnsupportedOneOs("输入服务接口不匹配")
        val remote = inputStub.getMethod("asInterface", IBinder::class.java).invoke(null, input)
            ?: throw UnsupportedOneOs("无法创建输入服务接口")
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "asBinder" -> callback
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CruiseTuneInputListener"
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        // Install a cleanup before registration: a transport error may arrive after server-side registration.
        val active = AtomicBoolean(true)
        val subscription = object : OneOsSubscription {
            override fun controlIndex(): Int {
                check(active.get()) { "OneOS subscription is closed" }
                return invoke(controlIndex, remote) as Int
            }
            override fun close() {
                if (active.compareAndSet(true, false)) {
                    callback.close()
                    invoke(unregister, remote, tag)
                }
            }
        }
        try {
            if (invoke(register, remote, listener, tag, SteeringKey.entries.map { it.code }.toIntArray()) == false)
                throw UnsupportedOneOs("车机拒绝注册按键监听")
            return subscription
        } catch (e: Exception) { runCatching { subscription.close() }; throw e }
    }
    private fun invoke(method: Method, target: Any, vararg args: Any?): Any? {
        try { return method.invoke(target, *args) }
        catch (e: InvocationTargetException) { throw (e.cause as? Exception ?: e) }
    }
}

/** Only the known system sender can deliver callbacks; callbacks never run player work on Binder threads. */
internal class OneOsListener(
    private val callbacks: Map<Int, SteeringEvent>, private val allowedUids: Set<Int>,
    private val receive: (Int, SteeringEvent) -> Unit,
) : Binder(), AutoCloseable {
    private val open = AtomicBoolean(true)
    init { attachInterface(null, OneOsDiscovery.LISTENER) }
    override fun close() { open.set(false) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(OneOsDiscovery.LISTENER); return true }
        val event = callbacks[code] ?: return super.onTransact(code, data, reply, flags)
        if (getCallingUid() !in allowedUids) throw SecurityException("Unexpected OneOS callback sender")
        data.enforceInterface(OneOsDiscovery.LISTENER)
        if (data.dataAvail() != if (event == SteeringEvent.RAW) 12 else 8) return false
        val key = data.readInt()
        data.readInt() // OEM-specific secondary field; do not reinterpret as an Android KeyEvent action.
        if (event == SteeringEvent.RAW) data.readInt()
        if (open.get()) receive(key, event)
        reply?.writeNoException()
        return true
    }
}
