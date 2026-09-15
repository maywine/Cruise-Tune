package com.cruisetune.player.steering

import android.app.Application
import android.content.*
import android.content.pm.*
import android.os.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OneOsConnectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private class ContextFixture(base: Context) : ContextWrapper(base) {
        var connection: ServiceConnection? = null
        var binds = 0
        var unbinds = 0
        var deny = false
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (deny) throw SecurityException("fixture permission denied")
            assertEquals(OneOsDiscovery.PACKAGE, intent.component!!.packageName)
            this.connection = connection; binds++; return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
    private fun installService(system: Boolean = true) {
        val info = ApplicationInfo().apply { packageName = OneOsDiscovery.PACKAGE; uid = Process.myUid(); if (system) flags = ApplicationInfo.FLAG_SYSTEM }
        val service = ServiceInfo().apply {
            name = OneOsDiscovery.PACKAGE + ".OneOSApiService"; packageName = OneOsDiscovery.PACKAGE
            applicationInfo = info; exported = true; enabled = true
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = OneOsDiscovery.PACKAGE; applicationInfo = info; services = arrayOf(service) })
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Expected OneOS connection state")
    }
    @Test fun absentServiceDoesNotBindOrPollForever() {
        val context = ContextFixture(app); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var status = ""
        val connection = OneOsConnection(context, scope, { _, value, _ -> status = value }, { fixtureContract() }) { _, _, _ -> }
        try {
            connection.start(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
            assertTrue(status.contains("没有")); assertEquals(0, context.binds)
        } finally { connection.stop(); scope.cancel() }
    }
    @Test fun deniedPermissionAndNonSystemImpersonationDoNotRetry() {
        for (system in listOf(true, false)) {
            installService(system)
            val context = ContextFixture(app).apply { deny = true }; val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var status = ""
            val connection = OneOsConnection(context, scope, { _, value, _ -> status = value }, { fixtureContract() }) { _, _, _ -> }
            try {
                connection.start(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
                assertTrue(if (system) status.contains("权限") else status.contains("身份")); assertEquals(0, context.binds)
            } finally { connection.stop(); scope.cancel() }
        }
    }
    @Test fun disconnectRebindsOnceAndOldCallbackCannotDeliverAfterStop() {
        installService()
        val context = ContextFixture(app); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var connected = false; var events = 0
        val client = OneOsConnection(context, scope, { active, _, _ -> connected = active }, { fixtureContract() }) { _, _, _ -> events++ }
        val component = ComponentName(OneOsDiscovery.PACKAGE, OneOsDiscovery.PACKAGE + ".OneOSApiService")
        val input = FixtureInputBinder()
        try {
            client.start(); context.connection!!.onServiceConnected(component, input); await { connected }
            assertEquals(1, input.registrations)
            val old = checkNotNull(input.listener)
            context.connection!!.onServiceDisconnected(component); assertFalse(connected)
            await { input.unregistrations == 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(2, context.binds)
            context.connection!!.onServiceConnected(component, input); await { connected }
            assertEquals(2, input.registrations)
            client.stop(); await { input.unregistrations == 2 }
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(OneOsDiscovery.LISTENER); data.writeInt(200087); data.writeInt(0)
                old.transact(12, data, reply, 0)
            } finally { data.recycle(); reply.recycle() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertEquals(0, events); assertEquals(2, context.binds); assertEquals(2, context.unbinds)
        } finally { client.stop(); scope.cancel() }
    }
}
