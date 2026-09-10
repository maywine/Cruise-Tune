package com.cruisetune.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cruisetune.player.core.SourceKind
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only: uses the account explicitly authorized in the test emulator. */
@RunWith(AndroidJUnit4::class)
class DirectQuarkDeviceTest {
    @Test fun authorizedMusicSurvivesRefreshAndSupportsRangeRead() = runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("realQuark")=="true")
        val app=instrument.targetContext.applicationContext as CruiseApplication
        val account=app.preferences.getString("quarkDirectAccount",null)
        assertNotNull("Scan the Quark QR in this emulator first",account)
        val before=app.openConnections.sessions.fresh(account!!)
        val after=app.openConnections.sessions.refreshAfterFailure(account,before.generation)
        assertEquals(before.accountId,after.accountId)
        assertTrue(after.generation>before.generation)
        val restored=app.openConnections.sessions.fresh(account)
        assertEquals(after.generation,restored.generation)
        val provider=app.openConnections.provider(account)
        val source=app.database.sources().firstOrNull { it.kind==SourceKind.QUARK_OPEN && it.accountId==account }
        val queue=java.util.ArrayDeque<String>().apply { add(source?.rootId ?: "0") }
        val visited=mutableSetOf<String>()
        var audio:com.cruisetune.player.core.RemoteEntry?=null
        while(queue.isNotEmpty() && visited.size<30 && audio==null) {
            val parent=queue.removeFirst();if(!visited.add(parent))continue
            val entries=provider.listChildren(parent)
            audio=entries.firstOrNull { !it.isDirectory && com.cruisetune.player.core.AudioFiles.mime(it.name)!=null }
            entries.filter { it.isDirectory }.forEach { queue.add(it.id) }
        }
        assertNotNull("No audio found within the authorized folders",audio)
        val media=provider.resolve(audio!!.id)
        app.streamHttp.newCall(Request.Builder().url(media.url).header("Range","bytes=0-65535").apply { media.headers.forEach{(k,v)->header(k,v)} }.build()).execute().use { response ->
            assertEquals("Range read must return partial data",206,response.code)
            val body=response.body!!; val buffer=ByteArray(4096);var read=0
            body.byteStream().use { input -> while(read<65536){val n=input.read(buffer,0,minOf(buffer.size,65536-read));if(n<0)break;read+=n} }
            assertEquals(65536,read)
        }
    }
}
