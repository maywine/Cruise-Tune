package com.cruisetune.player.steering

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

internal class UnsupportedOneOs(message: String) : Exception(message)
internal interface OneOsSubscription : AutoCloseable { fun controlIndex(): Int }

/** Versioned client profile recovered from the supplied sample, not a universal vendor SDK. */
internal object OneOsContract {
    const val PACKAGE = "com.geely.service.oneosapi"
    const val ROOT = "com.geely.lib.oneosapi.IServiceManager"
    const val INPUT = "com.geely.lib.oneosapi.input.IInputManager"
    const val LISTENER = "com.geely.lib.oneosapi.input.IInputListener"
    val callbacks = mapOf(1 to SteeringEvent.RAW, 2 to SteeringEvent.SHORT, 3 to SteeringEvent.HOLD_START,
        4 to SteeringEvent.HOLD_END, 5 to SteeringEvent.LONG, 6 to SteeringEvent.NATIVE_DOUBLE)
    // Union of service IDs observed in the sample's lookup paths; only the known getService
    // transaction 2 is called. Never try root transaction 1 (addService in the recovered SDK).
    private val candidates = (listOf(8, 12, 16, 20, 24, 28, 32, 36, 40) + (0..11)).distinct()
    fun input(root: IBinder, diagnose: (String) -> Unit = {}): IBinder {
        val descriptor = root.interfaceDescriptor
        diagnose("根接口：${descriptor.orEmpty()}")
        if (descriptor == INPUT) return root
        if (descriptor != ROOT) throw UnsupportedOneOs("OneOS 主服务接口不匹配")
        for (id in candidates) {
            val request = Parcel.obtain(); val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(ROOT); request.writeInt(id)
                if (!root.transact(2, request, response, 0)) throw UnsupportedOneOs("OneOS 不支持当前服务查询格式")
                response.readException()
                val binder = response.readStrongBinder()
                val type = binder?.interfaceDescriptor
                diagnose("服务 $id：${type ?: "未提供"}")
                if (type == INPUT) return checkNotNull(binder)
            } finally { response.recycle(); request.recycle() }
        }
        throw UnsupportedOneOs("未找到匹配的输入服务，请复制诊断信息")
    }
    fun register(input: IBinder, listener: OneOsListener, tag: String, diagnose: (String) -> Unit = {}): OneOsSubscription {
        if (input.interfaceDescriptor != INPUT) throw UnsupportedOneOs("输入服务接口不匹配")
        val request = Parcel.obtain(); val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(INPUT)
            request.writeStrongBinder(listener)
            request.writeString(tag)
            request.writeIntArray(SteeringKey.subscribedCodes)
            if (!input.transact(3, request, response, 0)) throw UnsupportedOneOs("车机未接受当前监听注册格式")
            response.readException()
            // No business return type was verified. Never interpret an extra zero as boolean
            // failure (it might be an integer success code). Actual callbacks confirm delivery.
            if (response.dataAvail() != 0) diagnose("监听回复附加 ${response.dataAvail()} 字节，尚未解释；等待实际回调确认")
        } catch (e: Exception) { listener.close(); throw e }
        finally { response.recycle(); request.recycle() }
        return object : OneOsSubscription {
            @Volatile private var closed = false
            override fun controlIndex(): Int {
                check(!closed)
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(INPUT)
                    if (!input.transact(5, data, reply, 0)) throw UnsupportedOneOs("车机未提供当前菜单查询格式")
                    reply.readException()
                    if (reply.dataAvail() != 4) throw UnsupportedOneOs("车机菜单回复格式不匹配")
                    return reply.readInt()
                } finally { reply.recycle(); data.recycle() }
            }
            override fun close() {
                closed = true; listener.close()
                // No verified remote unregister transaction exists in the sample. The dedicated
                // bound process is terminated on disable, making this callback Binder dead.
            }
        }
    }
}

/** A private Binder capability passed only to the verified system input endpoint. The endpoint
 * may delegate callbacks to another UID; do not assume it shares the root service's process. */
internal class OneOsListener(receive: (SteeringSignal) -> Unit, diagnose: (String) -> Unit = {}) : Binder(), AutoCloseable {
    private data class Delivery(val receive: (SteeringSignal) -> Unit, val diagnose: (String) -> Unit)
    private val delivery = AtomicReference<Delivery?>(Delivery(receive, diagnose))
    init { attachInterface(null, OneOsContract.LISTENER) }
    override fun close() { delivery.set(null) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(OneOsContract.LISTENER); return true }
        val event = OneOsContract.callbacks[code] ?: return super.onTransact(code, data, reply, flags)
        data.enforceInterface(OneOsContract.LISTENER)
        val expected = if (event == SteeringEvent.RAW) 12 else 8
        if (data.dataAvail() != expected) {
            delivery.get()?.diagnose?.invoke("回调 $code 长度不匹配：${data.dataAvail()}，预期 $expected")
            return false
        }
        val signal = SteeringSignal(data.readInt(), event, data.readInt(), if (event == SteeringEvent.RAW) data.readInt() else 0,
            getCallingUid(), SystemClock.uptimeMillis())
        delivery.get()?.receive?.invoke(signal)
        reply?.writeNoException(); return true
    }
}
