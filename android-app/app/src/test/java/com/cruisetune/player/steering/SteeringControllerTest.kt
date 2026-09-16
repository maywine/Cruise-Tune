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
        private lateinit var incoming: (SteeringSignal) -> Unit
        val controller = SteeringController(app, scope, prefs, status, connectionFactory = { report, _, receive ->
            incoming = receive
            object : SteeringConnection {
                override fun start() { report(true, "Synthetic connected", "Synthetic only") }
                override fun stop() {}
                override suspend fun controlIndex() = menu.get()
            }
        }) { action, valid -> if (valid()) actions += action }
        fun event(event: SteeringEvent, code: Int = SteeringKey.RIGHT.code) {
            incoming(SteeringSignal(code, event, 0, 0, 10321, SystemClock.uptimeMillis()))
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
            await { f.status.value.lastAction.contains("诊断") || f.status.value.lastAction.contains("检测") }
            f.event(SteeringEvent.LONG); f.event(SteeringEvent.HOLD_END); f.event(SteeringEvent.SHORT)
            advance(500); assertTrue(f.actions.isEmpty())
        }
    }
    @Test fun disablingDropsPendingSingleAndReturnsStandardMediaKeys() {
        Fixture().use { f ->
            await { f.status.value.menu.contains("媒体") }
            assertFalse(f.controller.onMediaButton(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
            assertEquals(0L, f.status.value.standardEvents)
            f.event(SteeringEvent.SHORT); await { f.pendingSingle() }
            f.prefs.edit().putBoolean(SteeringSettings.ENABLED, false).commit(); advance(500)
            assertTrue(f.actions.isEmpty()); assertFalse(f.controller.onMediaButton(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)))
        }
    }
    @Test fun registrationAloneAndDiagnosticsNeverSwallowMediaKeys() {
        Fixture().use { f ->
            await { f.status.value.menu.contains("媒体") }
            assertFalse(f.controller.onMediaButton(KeyEvent(100,100,KeyEvent.ACTION_DOWN,87,0)))
            assertFalse(f.controller.onMediaButton(KeyEvent(100,120,KeyEvent.ACTION_UP,87,0)))
            f.prefs.edit().putBoolean(SteeringSettings.DETECT_ONLY,true).commit(); advance(20)
            f.menu.set(null); f.event(SteeringEvent.SHORT,85)
            assertEquals(1L,f.status.value.receivedEvents)
            assertTrue(f.status.value.lastEvent.contains("播放键")); assertTrue(f.actions.isEmpty())
            assertFalse(f.controller.onMediaButton(KeyEvent(200,200,KeyEvent.ACTION_DOWN,85,0)))
            assertEquals(2L,f.status.value.standardEvents)
        }
    }
    @Test fun systemFirstAndOneOsFirstEachExecuteOnlyOneRoute() {
        Fixture().use { f ->
            await { f.status.value.menu.contains("媒体") }
            assertFalse(f.controller.onMediaButton(KeyEvent(100,100,KeyEvent.ACTION_DOWN,87,0)))
            assertFalse(f.controller.onMediaButton(KeyEvent(100,110,KeyEvent.ACTION_UP,87,0)))
            f.event(SteeringEvent.SHORT)
            await { f.status.value.lastAction.contains("系统媒体路径") }; advance(400)
            assertTrue(f.actions.isEmpty())
            f.event(SteeringEvent.SHORT); await { f.pendingSingle() }
            assertTrue(f.controller.onMediaButton(KeyEvent(200,200,KeyEvent.ACTION_DOWN,87,0)))
            assertTrue(f.controller.onMediaButton(KeyEvent(200,220,KeyEvent.ACTION_UP,87,0)))
            await { f.actions.isNotEmpty() }; assertEquals(listOf(SteeringAction.NEXT),f.actions)
        }
    }

}
