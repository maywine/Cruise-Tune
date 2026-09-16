package com.cruisetune.player.steering

import android.app.Application
import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/** Fake service implements only the byte layout recovered from the sample. No vendor Java SDK. */
internal class FixtureInputBinder : Binder() {
    @Volatile var listener: IBinder? = null
    @Volatile var registrations = 0
    val transactions = mutableListOf<Int>()
    var reject = false
    var controlIndex = 2
    init { attachInterface(null, OneOsContract.INPUT) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(OneOsContract.INPUT); transactions += code
        assertEquals(0, flags)
        when (code) {
            3 -> {
                listener = data.readStrongBinder(); assertTrue(data.readString()!!.contains("Steering"))
                assertArrayEquals(intArrayOf(200085,200088,200087,85), data.createIntArray())
                assertEquals(0, data.dataAvail()); registrations++
                if (reject) return false
                reply!!.writeNoException()
            }
            5 -> { assertEquals(0, data.dataAvail()); reply!!.writeNoException(); reply.writeInt(controlIndex) }
            else -> error("Unknown transaction $code")
        }
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class OneOsContractTest {
    @Test fun discoveryOnlyUsesVerifiedLookupAndValidatesInputDescriptor() {
        val input = FixtureInputBinder(); val requested = mutableListOf<Int>()
        val root = object : Binder() {
            init { attachInterface(null, OneOsContract.ROOT) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(2, code); data.enforceInterface(OneOsContract.ROOT)
                val id = data.readInt(); requested += id
                reply!!.writeNoException()
                reply.writeStrongBinder(if (id == 7) input else Binder().apply { attachInterface(null, "unrelated.service") })
                return true
            }
        }
        val diagnostics = mutableListOf<String>()
        assertSame(input, OneOsContract.input(root, diagnostics::add))
        assertEquals(requested.size, requested.distinct().size)
        assertTrue(7 in requested); assertTrue(diagnostics.last().contains(OneOsContract.INPUT))
    }
    @Test fun wrongDescriptorIsNeverUsedForRegistration() {
        val wrong = Binder().apply { attachInterface(null, "unexpected") }
        assertThrows(UnsupportedOneOs::class.java) { OneOsContract.input(wrong) }
        assertThrows(UnsupportedOneOs::class.java) { OneOsContract.register(wrong, OneOsListener({}), "Steering") }
    }
    @Test fun directRegistrationMenuAndCallbacksWorkWithoutVendorClasses() {
        assertThrows(ClassNotFoundException::class.java) { Class.forName(OneOsContract.INPUT + "\$Stub") }
        val remote = FixtureInputBinder(); val events = mutableListOf<SteeringSignal>()
        val listener = OneOsListener(events::add)
        val subscription = OneOsContract.register(remote, listener, "CruiseTune.Steering")
        assertEquals(2, subscription.controlIndex()); remote.controlIndex = 3; assertEquals(3, subscription.controlIndex())
        send(listener, 2, 85, 17)
        assertEquals(1, events.size); assertEquals(85, events.single().keyCode); assertEquals(17, events.single().parameter)
        assertEquals(SteeringEvent.SHORT, events.single().event)
        assertEquals(SteeringKey.CENTER, SteeringKey.fromCode(85))
        subscription.close(); subscription.close(); send(listener, 2)
        assertEquals(1, events.size)
        assertEquals(listOf(3,5,5), remote.transactions) // Never invent a remote unregister transaction.
        assertThrows(IllegalStateException::class.java) { subscription.controlIndex() }
    }
    @Test fun delegatedUidIsRecordedAndMalformedPayloadIsRejected() {
        val events = mutableListOf<SteeringSignal>(); val log = mutableListOf<String>()
        val listener = OneOsListener(events::add, log::add)
        ShadowBinder.setCallingUid(10321)
        try {
            send(listener, 1, 200087, 1, 99)
            assertEquals(10321, events.single().callerUid); assertEquals(99, events.single().extra)
            assertFalse(send(listener, 2, malformed = true)); assertEquals(1, events.size)
            assertTrue(log.single().contains("长度不匹配"))
        } finally { ShadowBinder.reset() }
    }
    @Test fun wrongInterfaceTokenCannotDeliverAnEvent() {
        val listener = OneOsListener({ fail("untrusted token") })
        assertThrows(SecurityException::class.java) { send(listener, 2, token = "wrong.interface") }
    }
    @Test fun rejectedTransactionClosesTheLocalCallback() {
        val remote = FixtureInputBinder().apply { reject = true }
        val listener = OneOsListener({ fail("closed callback") })
        assertThrows(UnsupportedOneOs::class.java) { OneOsContract.register(remote, listener, "Steering") }
        send(listener, 2); assertEquals(listOf(3), remote.transactions)
    }
    private fun send(listener: IBinder, code: Int, key: Int = 200087, parameter: Int = 0, extra: Int = 0,
        malformed: Boolean = false, token: String = OneOsContract.LISTENER): Boolean {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(token); data.writeInt(key)
            if (!malformed) data.writeInt(parameter)
            if (code == 1) data.writeInt(extra)
            val accepted = listener.transact(code, data, reply, 0)
            if (accepted) reply.readException()
            accepted
        } finally { data.recycle(); reply.recycle() }
    }
}
