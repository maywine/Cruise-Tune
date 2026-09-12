package com.cruisetune.player.data

import android.app.Application
import com.cruisetune.player.core.*
import com.cruisetune.player.data.open.OpenConnections
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.charset.Charset

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class LyricsRepositoryTest {
    @get:Rule val temporary=TemporaryFolder()

    private fun exercise(provider: MusicProvider, block: suspend (LyricsRepository,LibraryDatabase) -> Unit) = runBlocking {
        val app=RuntimeEnvironment.getApplication();app.deleteDatabase("cruise-library.db")
        val db=LibraryDatabase(app);val vault=CredentialVault(app);val http=OkHttpClient()
        val library=LibraryRepository(app,db,vault,http,OpenConnections(app,vault,http,http),providerFactory={_,_->provider})
        try { block(LyricsRepository(app,library,http),db) } finally { db.close();app.deleteDatabase("cruise-library.db") }
    }

    @Test fun remoteLyricsAreResolvedOnlyWithinTheTracksParentFolder() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[00:01]Parent folder lyrics"));server.start()
            val visited=mutableListOf<String>()
            val provider=object:MusicProvider {
                override suspend fun listChildren(parentId:String):List<RemoteEntry> {
                    visited+=parentId
                    return when(parentId) {
                        "root" -> listOf(RemoteEntry("album","Album",true,0,""),RemoteEntry("wrong","Song.lrc",false,10,""))
                        else -> listOf(RemoteEntry("lyrics","Song.LRC",false,40,""))
                    }
                }
                override suspend fun resolve(fileId:String):ReadRequest {
                    assertEquals("lyrics",fileId)
                    return ReadRequest(server.url("/lyrics").toString(),mapOf("X-Test-Source" to "synthetic"))
                }
            }
            exercise(provider) { repo,db ->
                val source=MusicSource("source",SourceKind.QUARK,"Music","root","synthetic")
                db.saveSource(source)
                val track=Track("song",source.id,"file","Song",relativePath="Album/Song.flac")
                assertEquals("Parent folder lyrics",repo.load(track)!!.at(1000).current)
                assertEquals(listOf("root","album"),visited)
                assertEquals("synthetic",server.takeRequest().getHeader("X-Test-Source"))
            }
        }
    }

    @Test fun missingSidecarDoesNotResolveAudioOrAnUnrelatedLyric() {
        val provider=object:MusicProvider {
            override suspend fun listChildren(parentId:String)=listOf(RemoteEntry("other","Different.lrc",false,10,""))
            override suspend fun resolve(fileId:String):ReadRequest=error("No file should be downloaded")
        }
        exercise(provider) { repo,db ->
            db.saveSource(MusicSource("source",SourceKind.QUARK_OPEN,"Music","root","synthetic"))
            assertNull(repo.load(Track("song","source","file","Song",relativePath="Song.flac")))
        }
    }

    @Test fun localFileLyricsStayBesideTheAudioAndReadLegacyChineseEncoding() {
        val folder=temporary.newFolder("Album")
        val audio=folder.resolve("Song.flac").apply { writeBytes(byteArrayOf()) }
        folder.resolve("Song.lrc").writeBytes("[00:00]合成歌词".toByteArray(Charset.forName("GB18030")))
        val provider=object:MusicProvider {
            override suspend fun listChildren(parentId:String):List<RemoteEntry> = error("No remote listing")
            override suspend fun resolve(fileId:String):ReadRequest=error("No remote reads")
        }
        exercise(provider) { repo,db ->
            db.saveSource(MusicSource("source",SourceKind.LOCAL,"Local","unused"))
            assertEquals("合成歌词",repo.load(Track("song","source","file","Song",relativePath="Album/Song.flac",localUri=audio.toURI().toString()))!!.at(0).current)
        }
    }

    @Test fun unicodeBomsAndUntrustedFileSizesAreHandled() {
        val text="[00:00]测试"
        assertEquals(text,LyricsRepository.decode(("\uFEFF"+text).toByteArray()))
        assertEquals(text,LyricsRepository.decode(byteArrayOf(0xff.toByte(),0xfe.toByte())+text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text,LyricsRepository.decode(byteArrayOf(0xfe.toByte(),0xff.toByte())+text.toByteArray(Charsets.UTF_16BE)))
        assertTrue(runCatching { LyricsRepository.readBounded(ByteArray(LrcParser.MAX_BYTES+1).inputStream()) }.exceptionOrNull() is UserError)
    }

    @Test fun ambiguousNamesDoNotAttachAnotherFilesLyrics() {
        assertEquals("Song.lrc",LyricsRepository.match("Song.lrc",listOf("Song.lrc","SONG.LRC")){it})
        assertNull(LyricsRepository.match("Song.lrc",listOf("song.lrc","SONG.LRC")){it})
        assertNull(LyricsRepository.match("Song.lrc",listOf("Song.lrc","Song.lrc")){it})
    }
}
