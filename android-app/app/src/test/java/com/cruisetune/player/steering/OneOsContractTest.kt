package com.cruisetune.player.steering

import android.app.Application
import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic SDK intentionally uses different callback/registration numbers from the sample APK. */
interface FixtureListener : IInterface {
    fun onKeyCodeEvent(a: Int, b: Int, c: Int)
    fun onShortClick(a: Int, b: Int)
    fun onHoldingPressStarted(a: Int, b: Int)
    fun onHoldingPressStopped(a: Int, b: Int)
    fun onLongPressTriggered(a: Int, b: Int)
    fun onDoubleClick(a: Int, b: Int)
    class Stub {
        companion object {
            @JvmField val TRANSACTION_onKeyCodeEvent = 11
            @JvmField val TRANSACTION_onShortClick = 12
            @JvmField val TRANSACTION_onHoldingPressStarted = 13
            @JvmField val TRANSACTION_onHoldingPressStopped = 14
            @JvmField val TRANSACTION_onLongPressTriggered = 15
            @JvmField val TRANSACTION_onDoubleClick = 16
        }
    }
}
interface FixtureInput : IInterface {
    fun registerKeyListener(listener: FixtureListener, tag: String, keys: IntArray): Boolean
    fun unregisterKeyListener(tag: String)
    fun getControlIndex(): Int
    class Stub {
        companion object {
            @JvmStatic fun asInterface(binder: IBinder): FixtureInput = object : FixtureInput {
                override fun asBinder() = binder
                override fun registerKeyListener(listener: FixtureListener, tag: String, keys: IntArray): Boolean {
                    val p = Parcel.obtain(); val r = Parcel.obtain()
                    try {
                        p.writeInterfaceToken(OneOsDiscovery.INPUT); p.writeStrongBinder(listener.asBinder()); p.writeString(tag); p.writeIntArray(keys)
                        check(binder.transact(17, p, r, 0)); r.readException(); return r.readInt() == 1
                    } finally { p.recycle(); r.recycle() }
                }
                override fun unregisterKeyListener(tag: String) {
                    val p = Parcel.obtain(); val r = Parcel.obtain()
                    try {
                        p.writeInterfaceToken(OneOsDiscovery.INPUT); p.writeString(tag)
                        check(binder.transact(29, p, r, 0)); r.readException()
                    } finally { p.recycle(); r.recycle() }
                }
                override fun getControlIndex(): Int {
                    val p = Parcel.obtain(); val r = Parcel.obtain()
                    try {
                        p.writeInterfaceToken(OneOsDiscovery.INPUT)
                        check(binder.transact(41, p, r, 0)); r.readException(); return r.readInt()
                    } finally { p.recycle(); r.recycle() }
                }
            }
        }
    }
}
internal fun fixtureContract() = OneOsContract.fromTypes(FixtureInput::class.java, FixtureInput.Stub::class.java, FixtureListener::class.java, FixtureListener.Stub::class.java)
internal class FixtureInputBinder : Binder() {
    @Volatile var listener: IBinder? = null
    @Volatile var registrations = 0
    @Volatile var unregistrations = 0
    var reject = false
    var controlIndex = 2
    init { attachInterface(null, OneOsDiscovery.INPUT) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(OneOsDiscovery.INPUT)
        when (code) {
            17 -> {
                listener = data.readStrongBinder(); assertTrue(data.readString()!!.contains("Steering"))
                assertArrayEquals(SteeringKey.entries.map { it.code }.toIntArray(), data.createIntArray())
                registrations++; reply!!.writeNoException(); reply.writeInt(if (reject) 0 else 1)
            }
            29 -> { data.readString(); unregistrations++; reply!!.writeNoException() }
            41 -> { reply!!.writeNoException(); reply.writeInt(controlIndex) }
            else -> error("Unverified outbound transaction $code")
        }
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class OneOsContractTest {
    @Test fun discoveryOnlyCallsGetServiceAndRejectsUnrelatedBinder() {
        val input = FixtureInputBinder(); val requested = mutableListOf<Int>()
        val root = object : Binder() {
            init { attachInterface(null, OneOsDiscovery.ROOT) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(2, code); data.enforceInterface(OneOsDiscovery.ROOT)
                val id = data.readInt(); requested += id
                reply!!.writeNoException()
                reply.writeStrongBinder(if (id == 16) input else Binder().apply { attachInterface(null, "unrelated.service") })
                return true
            }
        }
        assertSame(input, OneOsDiscovery.input(root)); assertEquals(listOf(8,12,16), requested)
    }
    @Test fun descriptorMismatchNeverCallsAnOutboundMethod() {
        val wrong = object : Binder() {
            init { attachInterface(null, "unexpected") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = error("Must not transact")
        }
        assertThrows(UnsupportedOneOs::class.java) { OneOsDiscovery.input(wrong) }
    }
    @Test fun namedSdkRegistrationAndUnregistrationAreUsedInsteadOfGuessedTransactions() {
        val remote = FixtureInputBinder(); val events = mutableListOf<SteeringEvent>()
        val listener = OneOsListener(fixtureContract().callbacks, setOf(Process.myUid())) { _, e -> events += e }
        val subscription = fixtureContract().register(remote, listener, "CruiseTune.Steering")
        assertEquals(2, subscription.controlIndex()); remote.controlIndex = 3; assertEquals(3, subscription.controlIndex())
        send(listener, 12); assertEquals(listOf(SteeringEvent.SHORT), events)
        subscription.close(); subscription.close(); send(listener, 12)
        assertEquals(1, remote.registrations); assertEquals(1, remote.unregistrations); assertEquals(1, events.size)
    }
    @Test fun rejectedRegistrationIsCleanedUpAndDoesNotRemainLive() {
        val remote = FixtureInputBinder().apply { reject = true }
        val listener = OneOsListener(fixtureContract().callbacks, setOf(Process.myUid())) { _, _ -> fail("closed callback") }
        assertThrows(UnsupportedOneOs::class.java) { fixtureContract().register(remote, listener, "CruiseTune.Steering") }
        assertEquals(1, remote.unregistrations); send(listener, 12)
    }
    @Test fun wrongSenderAndMalformedCallbackCannotControlPlayer() {
        val forbidden = OneOsListener(fixtureContract().callbacks, setOf(-1)) { _, _ -> fail("untrusted sender") }
        assertThrows(SecurityException::class.java) { send(forbidden, 12) }
        val valid = OneOsListener(fixtureContract().callbacks, setOf(Process.myUid())) { _, _ -> fail("malformed payload") }
        assertFalse(send(valid, 12, malformed = true))
    }
    private fun send(listener: IBinder, code: Int, malformed: Boolean = false): Boolean {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(OneOsDiscovery.LISTENER); data.writeInt(200087)
            if (!malformed) data.writeInt(0)
            val accepted = listener.transact(code, data, reply, 0)
            if (accepted) reply.readException()
            accepted
        } finally { data.recycle(); reply.recycle() }
    }
}
