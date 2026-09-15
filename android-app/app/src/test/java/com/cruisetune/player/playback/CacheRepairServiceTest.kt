package com.cruisetune.player.playback

import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.core.*
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,33],application=CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class CacheRepairServiceTest {
    private val app get()=RuntimeEnvironment.getApplication() as CruiseApplication
    private class Fixture(val owner:ServiceController<PlaybackService>,val real:ExoPlayer,val tracks:List<Track>,
        val failures:AtomicBoolean,val calls:ConcurrentHashMap<String,AtomicInteger>)
    private fun await(message:String, condition:()->Boolean){
        val end=System.nanoTime()+10_000_000_000L
        while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));if(condition())return;Thread.sleep(5)}
        fail(message)
    }
    private fun invoke(service:PlaybackService,name:String)=PlaybackService::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(service)
    private fun fixture(permanent:Boolean=false):Fixture {
        shadowOf(app).grantPermissions(app.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        ShadowStatFs.registerStats(app.filesDir.absolutePath,2000000,1500000,1500000)
        val source=MusicSource("repair-service",SourceKind.QUARK,"Synthetic","root")
        val tracks=(0..3).map{Track("repair-$it",source.id,"$it","Synthetic $it",size=8192)}
        app.database.saveSource(source);app.database.replaceScan(source.id,tracks)
        app.database.saveQueue(PlaybackSnapshot(1,tracks.mapIndexed{i,t->QueueEntry(t,i)}))
        val calls=ConcurrentHashMap<String,AtomicInteger>();val failures=AtomicBoolean(true)
        ReflectionHelpers.setField(app.media,"network",DataSource.Factory{
            val bytes=ByteArrayDataSource(ByteArray(8192){7})
            object:DataSource by bytes {
                override fun open(spec:DataSpec):Long {
                    calls.getOrPut(checkNotNull(spec.key)){AtomicInteger()}.incrementAndGet()
                    if(spec.key==tracks[0].cacheKey && failures.get()) {
                        if(permanent)throw UserError("File access unavailable")
                        throw SocketTimeoutException()
                    }
                    return bytes.open(spec)
                }
            }
        })
        val owner=Robolectric.buildService(PlaybackService::class.java).create();val real=ReflectionHelpers.getField<ExoPlayer>(owner.get(),"player")
        await("Restore queue"){real.mediaItemCount==4}
        // Isolate the already-recovered playback state; all repair/cache I/O remains real.
        val proxy=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)){_,m,args->when(m.name){
            "getCurrentPosition"->2000L;"isPlaying"->true;"getPlayWhenReady"->true;"getPlaybackState"->Player.STATE_READY
            "getTotalBufferedDuration"->30000L;"getPlaybackSuppressionReason"->Player.PLAYBACK_SUPPRESSION_REASON_NONE;"getPlayerError"->null
            else->m.invoke(real,*(args?:emptyArray()))
        }} as ExoPlayer
        ReflectionHelpers.setField(owner.get(),"player",proxy)
        val constructor=Class.forName("com.cruisetune.player.playback.PlaybackService\$Recovery").getDeclaredConstructor(
            Track::class.java,MediaCache.Bypass::class.java,Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}
        ReflectionHelpers.setField(owner.get(),"recovery",constructor.newInstance(tracks[0],app.media.beginBypass(tracks[0]),0L,false,0))
        invoke(owner.get(),"checkRecoveryProgress")
        return Fixture(owner,real,tracks,failures,calls)
    }
    private fun finish(f:Fixture){ReflectionHelpers.setField(f.owner.get(),"player",f.real);f.owner.destroy()}
    private fun calls(f:Fixture)=f.calls[f.tracks[0].cacheKey]?.get()?:0
    @Test fun repairRetryAndFutureThreeCachesProgressIndependently(){
        val f=fixture()
        try {
            await("Future three must cache while repair is waiting") {calls(f)>=2 && f.tracks.drop(1).all(app.media::isPrefetchComplete)}
            assertTrue(app.media.isBypassed(f.tracks[0].cacheKey))
            f.failures.set(false)
            await("Repair must finish when the network recovers") {ReflectionHelpers.getField<Any?>(f.owner.get(),"recovery")==null}
            assertTrue(app.media.hasCompleteStreamingCache(f.tracks[0]))
            assertFalse(app.media.isBypassed(f.tracks[0].cacheKey))
            assertTrue(f.tracks.drop(1).all(app.media::isPrefetchComplete))
        } finally {finish(f)}
    }
    @Test fun permanentRepairFailureDoesNotBlockFutureCachesOrRetryInvalidAccess(){
        val f=fixture(permanent=true)
        try {
            await("Future caches must survive a permanent current-file repair failure") {calls(f)==1 && f.tracks.drop(1).all(app.media::isPrefetchComplete)}
            f.real.repeatMode=Player.REPEAT_MODE_ONE
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertEquals(1,calls(f))
            assertFalse(ReflectionHelpers.getField<Job?>(f.owner.get(),"recoveryRepair")?.isActive==true)
            assertTrue(app.media.isBypassed(f.tracks[0].cacheKey)) // Do not reintroduce suspect bytes to active playback.
        } finally {finish(f)}
    }
    @Test fun screenOffCancelsRepairRetryAndPrefetch(){
        val f=fixture()
        try {
            await("Repair must attempt once"){calls(f)>0}
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            invoke(f.owner.get(),"pauseForScreenOff")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val before=f.calls.values.sumOf{it.get()}
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertNull(ReflectionHelpers.getField<Any?>(f.owner.get(),"recovery"))
            assertFalse(app.media.isBypassed(f.tracks[0].cacheKey))
            assertEquals(before,f.calls.values.sumOf{it.get()})
        } finally {finish(f)}
    }
}
