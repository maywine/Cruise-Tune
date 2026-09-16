package com.cruisetune.player.data

import android.app.Application
import com.cruisetune.player.core.*
import com.cruisetune.player.data.open.OpenConnections
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class SourceRegistrationTest {
    private fun exercise(block: suspend (LibraryRepository, LibraryDatabase, () -> Int) -> Unit) = runBlocking {
        val app=RuntimeEnvironment.getApplication();app.deleteDatabase("cruise-library.db")
        val db=LibraryDatabase(app);val vault=CredentialVault(app);val http=OkHttpClient();var scans=0
        val provider=object:MusicProvider {
            override suspend fun listChildren(parentId:String):List<RemoteEntry> {
                scans++;return listOf(RemoteEntry("file","Song.flac",false,100,"version"))
            }
            override suspend fun resolve(fileId:String)=error("No media reads expected during registration")
        }
        val repo=LibraryRepository(app,db,vault,http,OpenConnections(app,vault,http,http),providerFactory={_,_->provider})
        try {block(repo,db){scans}} finally {db.close();app.deleteDatabase("cruise-library.db")}
    }
    @Test fun concurrentRepeatRegistrationScansExactlyOnce() = exercise { repo,db,count ->
        val source=MusicSource("web",SourceKind.QUARK,"Music","root","account")
        coroutineScope { (1..12).map { async {repo.addAndScan(source,"[\"Music\"]")} }.awaitAll() }
        assertEquals(1,count());assertEquals(1,db.sources().size);assertEquals(1,db.tracks().size)
    }
    @Test fun confirmedSecondAccessReusesOneSourceAndNeverScansAgain() = exercise {repo,db,count ->
        val web=MusicSource("web",SourceKind.QUARK,"Music","web-root","web-session")
        val token=MusicSource("token",SourceKind.QUARK_OPEN,"Music","wrapper|token-root","token-session")
        repo.addAndScan(web,"[\"Music\"]")
        assertTrue(runCatching {repo.addAndScan(token,"[\"Music\"]")}.exceptionOrNull() is UserError)
        assertEquals(1,count())
        assertEquals(web,repo.addAndScan(token,"[\"Music\"]",confirmedExistingId=web.id))
        assertEquals(web,repo.addAndScan(token.copy(rootId="renewed|token-root"),"[\"Music\"]"))
        assertEquals(1,count());assertEquals(listOf(web),db.sources());assertEquals(1,db.tracks().size)
        repo.scan(web);assertEquals(2,count()) // Only an explicit refresh rereads the directory.
    }
    @Test fun explicitlyDifferentAccountsKeepIndependentSources() = exercise {repo,db,count ->
        repo.addAndScan(MusicSource("web",SourceKind.QUARK,"Music","root","one"),"[\"Music\"]")
        repo.addAndScan(MusicSource("token",SourceKind.QUARK_OPEN,"Music","root","two"),"[\"Music\"]",independent=true)
        assertEquals(2,count());assertEquals(2,db.sources().size)
    }
    @Test fun initialImportReusesPickerListingRatherThanReadingTheRootTwice() = exercise {repo,db,count ->
        val source=MusicSource("web",SourceKind.QUARK,"Music","root","account")
        val alreadyListed=listOf(RemoteEntry("root-song","Root.flac",false,100,"v"),RemoteEntry("child","Album",true,0,""))
        repo.addAndScan(source,"[\"Music\"]",initialChildren=alreadyListed)
        assertEquals(1,count()) // Child directory only; the root result is reused.
        assertEquals(2,db.tracks().size)
        repo.addAndScan(source,"[\"Music\"]")
        assertEquals(1,count())
    }
    @Test fun removalRetiresAliasAndQueuedScanCannotResurrectSource() = exercise {repo,db,count ->
        val web=MusicSource("web",SourceKind.QUARK,"Music","root","one")
        val token=MusicSource("token",SourceKind.QUARK_OPEN,"Music","other-root","two")
        repo.addAndScan(web,"[\"Music\"]");repo.addAndScan(token,"[\"Music\"]",web.id)
        repo.removeSources(setOf(web.id),PlaybackSnapshot())
        repo.scan(web)
        assertEquals(1,count());assertNull(db.existingSource(token));assertTrue(db.sources().isEmpty())
    }
}
