package com.cruisetune.player.ui

import android.content.ComponentName
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.cruisetune.player.CruiseApplication
import com.cruisetune.player.R
import com.cruisetune.player.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], application = CruiseApplication::class)
@androidx.media3.common.util.UnstableApi
class QueueTabTest {
    private val app get() = RuntimeEnvironment.getApplication() as CruiseApplication
    @Before fun setup() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        RuntimeEnvironment.setQualifiers("w960dp-h540dp-land-mdpi")
    }
    private fun tab(a: MainActivity, label: String) = (a.findViewById<ViewGroup>(R.id.player_tabs)).let { row ->
        (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TouchButton>().single { it.text == label }
    }
    private fun timeline(a: MainActivity, count: Int) = ReflectionHelpers.callInstanceMethod<Unit>(a,"onQueueChanged",
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!,count))
    private fun await(condition: () -> Boolean) {
        val end=System.nanoTime()+5_000_000_000L
        while(System.nanoTime()<end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if(condition())return
            Thread.sleep(5)
        }
        fail("Expected media session connection")
    }
    @Test fun initialEmptyTimelineKeepsLibraryUntilSavedQueueArrives() {
        val owner=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            timeline(owner.get(),0);assertTrue(tab(owner.get(),"曲库").isSelected)
            timeline(owner.get(),3);assertTrue(tab(owner.get(),"队列").isSelected)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun pendingStartupDecisionSurvivesRecreationBeforeQueueLoads() {
        val owner=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            timeline(owner.get(),0);owner.recreate();timeline(owner.get(),3)
            assertTrue(tab(owner.get(),"队列").isSelected)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun explicitLibraryChoiceWinsOverLateRestorationAndLaterTrackChanges() {
        val owner=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            timeline(owner.get(),0);tab(owner.get(),"曲库").performClick()
            timeline(owner.get(),3);assertTrue(tab(owner.get(),"曲库").isSelected)
            owner.recreate();timeline(owner.get(),3);timeline(owner.get(),4)
            assertTrue(tab(owner.get(),"曲库").isSelected)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun browsingLibraryWhileQueueExistsIsNotInterruptedByPlaybackUpdates() {
        val owner=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            timeline(owner.get(),3);tab(owner.get(),"曲库").performClick()
            repeat(3) { timeline(owner.get(),3) }
            assertTrue(tab(owner.get(),"曲库").isSelected)
            tab(owner.get(),"队列").performClick();timeline(owner.get(),0)
            assertTrue(tab(owner.get(),"队列").isSelected)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun explicitPlayWithExistingQueueReturnsToQueue() {
        val player=ExoPlayer.Builder(app).build()
        player.setMediaItem(MediaItem.fromUri("file:///synthetic-not-played.wav"))
        val session=MediaSession.Builder(app,player).build()
        val future=MediaController.Builder(app,session.token).buildAsync()
        val owner=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            await { future.isDone }
            val controller=future.get();await { controller.mediaItemCount==1 }
            ReflectionHelpers.setField(owner.get(),"controller",controller)
            timeline(owner.get(),1);tab(owner.get(),"曲库").performClick()
            owner.get().findViewById<View>(R.id.player_play).performClick()
            assertTrue(tab(owner.get(),"队列").isSelected)
        } finally { MediaController.releaseFuture(future);owner.pause().stop().destroy();session.release();player.release() }
    }
}
