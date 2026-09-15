package com.cruisetune.player.steering

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** Compose the real controller, menu guard, gesture timers and persisted mappings.
 * Only the unavailable vehicle subscription is replaced; protocol/parcels have separate tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class SteeringControllerTest {
    private class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("controller-fixture", Context.MODE_PRIVATE).apply {
            edit().clear().putBoolean(SteeringSettings.ENABLED, true).commit()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val status = MutableStateFlow(SteeringStatus())
        val menu = AtomicReference<Int?>(2)
        val actions = mutableListOf<SteeringAction>()
        val controller = SteeringController(app, scope, prefs, status) { action, valid -> if (valid()) actions += action }
        init {
            val connection = ReflectionHelpers.getField<OneOsConnection>(controller, "connection")
            ReflectionHelpers.setField(connection, "subscription", object : OneOsSubscription {
                override fun controlIndex(): Int = menu.get() ?: throw IllegalStateException("Synthetic unavailable menu")
                override fun close() {}
            })
            // The device-absent probe leaves the requested connection enabled. Emulate its
            // successful subscription callback rather than introducing a production mock mode.
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "connectionChanged",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
                ReflectionHelpers.ClassParameter.from(String::class.java, "Synthetic connected"),
                ReflectionHelpers.ClassParameter.from(String::class.java, "Synthetic only"))
        }
        fun event(event: SteeringEvent) {
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "receive",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, SteeringKey.RIGHT.code),
                ReflectionHelpers.ClassParameter.from(SteeringEvent::class.java, event),
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, SystemClock.uptimeMillis()))
        }
        fun pendingSingle(): Boolean {
            val recognizer = ReflectionHelpers.getField<SteeringGestures>(controller, "gestures")
            val states = ReflectionHelpers.getField<Map<*, *>>(recognizer, "states")
            val state = states[SteeringKey.RIGHT] ?: return false
            return ReflectionHelpers.getField<Any?>(state, "firstShort") != null
        }
        override fun close() { controller.close(); scope.cancel() }
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            advance(10); if (condition()) return
            Thread.sleep(5)
        }
        fail("Expected composed steering pipeline result")
    }
    @Test fun realControllerCombinesDoubleClickAndSuppressesNativeDuplicate() {
        Fixture().use { f ->
            SteeringSettings(f.prefs).setAction(SteeringKey.RIGHT, SteeringGesture.DOUBLE, SteeringAction.PREVIOUS)
            await { f.status.value.menu.contains("媒体") }
            f.event(SteeringEvent.SHORT); await { f.pendingSingle() }
            advance(120); f.event(SteeringEvent.SHORT); f.event(SteeringEvent.NATIVE_DOUBLE)
            await { f.actions.isNotEmpty() }; advance(500)
            assertEquals(listOf(SteeringAction.PREVIOUS), f.actions)
        }
    }
    @Test fun menuChangedBeforeSingleDispatchPreventsPlaybackAndReturnDoesNotResurrectIt() {
        Fixture().use { f ->
            await { f.status.value.menu.contains("媒体") }
            f.event(SteeringEvent.SHORT); await { f.pendingSingle() }
            f.menu.set(3); advance(350)
            await { f.status.value.menu.contains("电话") }; assertTrue(f.actions.isEmpty())
            f.menu.set(2); advance(500); await { f.status.value.menu.contains("媒体") }
            advance(500); assertTrue(f.actions.isEmpty())
            f.event(SteeringEvent.SHORT); await { f.actions.isNotEmpty() }
            assertEquals(listOf(SteeringAction.NEXT), f.actions)
        }
    }
    @Test fun unknownMenuBlocksLongAndDetectedLongDoesNotExecute() {
        Fixture().use { f ->
            SteeringSettings(f.prefs).setAction(SteeringKey.RIGHT, SteeringGesture.LONG, SteeringAction.PAUSE)
            await { f.status.value.menu.contains("媒体") }
            f.menu.set(null); f.event(SteeringEvent.HOLD_START)
            await { f.status.value.menu.contains("无法确认") }; assertTrue(f.actions.isEmpty())
            f.menu.set(2); f.prefs.edit().putBoolean(SteeringSettings.DETECT_ONLY, true).commit()
            advance(500); await { f.status.value.menu.contains("媒体") }
            f.event(SteeringEvent.HOLD_START)
            await { f.status.value.lastAction.contains("检测") }
            f.event(SteeringEvent.LONG); f.event(SteeringEvent.HOLD_END); f.event(SteeringEvent.SHORT)
            advance(500); assertTrue(f.actions.isEmpty())
        }
    }
    @Test fun disablingDropsPendingSingleAndReturnsStandardMediaKeys() {
        Fixture().use { f ->
            await { f.status.value.menu.contains("媒体") }
            assertTrue(f.controller.ownsMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT))
            assertFalse(f.controller.ownsMediaButton(KeyEvent.KEYCODE_VOLUME_UP))
            f.event(SteeringEvent.SHORT); await { f.pendingSingle() }
            f.prefs.edit().putBoolean(SteeringSettings.ENABLED, false).commit(); advance(500)
            assertTrue(f.actions.isEmpty()); assertFalse(f.controller.ownsMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT))
        }
    }
}
