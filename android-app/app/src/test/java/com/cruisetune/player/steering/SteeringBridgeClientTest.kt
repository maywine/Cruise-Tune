package com.cruisetune.player.steering

import android.app.Application
import android.content.*
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
@Config(sdk = [28, 33], application = Application::class)
class SteeringBridgeClientTest {
    private class FixtureContext(base: Context) : ContextWrapper(base) {
        var bound: ServiceConnection? = null
        var count = 0; var unbound = 0
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(SteeringBridgeService::class.java.name, intent.component!!.className)
            bound = conn; count++; return true
        }
        override fun unbindService(conn: ServiceConnection) { unbound++ }
    }
    private class Bridge : ISteeringBridge.Stub() {
        @Volatile var sink: ISteeringSink? = null
        override fun start(receiver: ISteeringSink) { sink = receiver; receiver.onConnection(true, "Synthetic", "No vendor SDK") }
        override fun getControlIndex() = 2
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Expected bridge result")
    }
    @Test fun privateBridgeCarriesCallbacksAndStopRejectsOldGeneration() {
        val context = FixtureContext(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var connected = false; val events = mutableListOf<SteeringSignal>()
        val client = SteeringBridgeClient(context, scope, { yes, _, _ -> connected = yes }, {}, events::add)
        val name = ComponentName(context, SteeringBridgeService::class.java)
        val bridge = Bridge()
        try {
            client.start(); context.bound!!.onServiceConnected(name, bridge); await { connected }
            val sink = checkNotNull(bridge.sink)
            sink.onKey(85, SteeringEvent.SHORT.ordinal, 7, 9, 10321, SystemClock.uptimeMillis())
            await { events.size == 1 }
            assertEquals(10321, events.single().callerUid); assertEquals(7, events.single().parameter)
            var result: Int? = null
            scope.launch { result = client.controlIndex() }; await { result != null }; assertEquals(2, result)
            client.stop(); sink.onKey(85, SteeringEvent.SHORT.ordinal, 0, 0, 10321, SystemClock.uptimeMillis())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertEquals(1, events.size); assertEquals(1, context.unbound); assertEquals(1, context.count)
        } finally { client.stop(); scope.cancel() }
    }
}
