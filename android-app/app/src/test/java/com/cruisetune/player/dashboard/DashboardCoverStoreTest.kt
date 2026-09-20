package com.cruisetune.player.dashboard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = Application::class)
class DashboardCoverStoreTest {
    private fun artwork(): ByteArray = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(221, 187, 132))
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
    }.toByteArray()

    @Test fun artworkIsSavedAndAddressUsesTheAndroidVersionTransport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = DashboardCoverStore(RuntimeEnvironment.getApplication(), scope)
        val prepared = store.prepare("song", artwork(), "artwork-1")
        assertNotNull(prepared)
        assertEquals("song", prepared!!.trackId)
        if (Build.VERSION.SDK_INT >= 29) assertTrue(prepared.uri.startsWith("http://127.0.0.1:9090/"))
        else assertTrue(prepared.uri.startsWith("file:///"))
        store.close()
        scope.cancel()
    }

    @Test fun closedStoreRejectsLatePreparation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = DashboardCoverStore(RuntimeEnvironment.getApplication(), scope)
        store.close()
        assertNull(store.prepare("song", artwork(), "artwork-1"))
        scope.cancel()
    }
}
