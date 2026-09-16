package com.cruisetune.player.ui

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cruisetune.player.core.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class QueueFollowerTest {
    private val tracks=(0 until 100).map { Track("song-$it","source","file-$it","Song $it") }
    private fun withList(block:(Activity,RecyclerView,TrackAdapter,QueueFollower)->Unit) {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val adapter=TrackAdapter(true){}
        val list=RecyclerView(activity.get()).apply { layoutManager=LinearLayoutManager(context);this.adapter=adapter;itemAnimator=null }
        val follower=QueueFollower()
        try {
            activity.get().setContentView(list)
            follower.bind(list,adapter)
            submit(adapter,tracks,follower);settle(list)
            block(activity.get(),list,adapter,follower)
        } finally { follower.detach();activity.pause().stop().destroy() }
    }
    private fun submit(adapter:TrackAdapter,items:List<Track>,follower:QueueFollower) {
        adapter.submitList(items) { follower.onListCommitted() }
        val deadline=System.nanoTime()+3_000_000_000L
        while(adapter.currentList!=items && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2) }
        assertEquals(items,adapter.currentList)
    }
    private fun settle(list:RecyclerView) {
        repeat(5) {
            list.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY))
            list.layout(0,0,400,300)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
    }
    private fun first(list:RecyclerView)=(list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
    private fun browse(list:RecyclerView,position:Int) { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position,0);settle(list) }

    @Test fun selectedSongBecomesVisibleAndVisibleSelectionsDoNotMoveTheViewport() = withList { _,list,_,follower ->
        follower.update(true,"song-60",tracks);settle(list)
        assertEquals(60,first(list))
        follower.update(true,"song-61",tracks);settle(list)
        assertEquals(60,first(list))
        follower.update(true,"song-59",tracks);settle(list)
        assertEquals(59,first(list))
    }
    @Test fun repeatedPlaybackUpdatesDoNotPullManualBrowsingBack() = withList { _,list,_,follower ->
        follower.update(true,"song-60",tracks);settle(list)
        browse(list,5)
        repeat(4) { follower.update(true,"song-60",tracks);follower.onListCommitted();settle(list) }
        assertEquals(5,first(list))
        follower.revealCurrent();settle(list)
        assertEquals(60,first(list))
    }
    @Test fun touchAndFlingDeferFollowingAndKeepOnlyTheLatestTarget() = withList { _,list,_,follower ->
        follower.update(true,"song-5",tracks);settle(list)
        val down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,200f,140f,0)
        list.dispatchTouchEvent(down);down.recycle()
        follower.update(true,"song-60",tracks);follower.update(true,"song-80",tracks);settle(list)
        assertEquals(5,first(list))
        val cancel=MotionEvent.obtain(0,20,MotionEvent.ACTION_CANCEL,200f,140f,0)
        list.dispatchTouchEvent(cancel);cancel.recycle()
        ReflectionHelpers.callInstanceMethod<Unit>(list,"setScrollState",ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,RecyclerView.SCROLL_STATE_SETTLING))
        settle(list);assertEquals(5,first(list))
        ReflectionHelpers.callInstanceMethod<Unit>(list,"setScrollState",ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,RecyclerView.SCROLL_STATE_IDLE))
        settle(list);assertEquals(80,first(list))
    }
    @Test fun sortingWaitsForTheMatchingCommittedListAndFollowsBySongId() = withList { _,list,adapter,follower ->
        follower.update(true,"song-20",tracks);settle(list)
        browse(list,5)
        val reversed=tracks.reversed()
        follower.update(true,"song-20",reversed);settle(list)
        assertEquals(5,first(list))
        submit(adapter,reversed,follower);settle(list)
        assertEquals(79,first(list));assertEquals("song-20",adapter.currentList[first(list)].id)
    }
    @Test fun libraryAndEmptyQueueCancelPendingFollowRequests() = withList { _,list,adapter,follower ->
        follower.update(true,"song-60",tracks)
        follower.update(false,"song-60",tracks);settle(list)
        assertEquals(0,first(list))
        follower.update(true,"song-60",tracks);settle(list);assertEquals(60,first(list))
        browse(list,5)
        follower.update(true,"song-80",tracks)
        follower.update(true,null,emptyList());submit(adapter,emptyList(),follower);settle(list)
        assertEquals(0,adapter.itemCount)
    }
    @Test fun rebuildingTheListPreservesBrowsingUnlessTheCurrentSongChanged() = withList { activity,list,_,follower ->
        follower.update(true,"song-60",tracks);settle(list);browse(list,5)
        val position=list.layoutManager!!.onSaveInstanceState()
        val adapter=TrackAdapter(true){}
        val restored=RecyclerView(activity).apply { layoutManager=LinearLayoutManager(context);this.adapter=adapter;itemAnimator=null }
        follower.bind(restored,adapter);activity.setContentView(restored)
        submit(adapter,tracks,follower);restored.layoutManager!!.onRestoreInstanceState(position)
        follower.update(true,"song-60",tracks);settle(restored)
        assertEquals(5,first(restored))
        follower.update(true,"song-80",tracks);settle(restored)
        assertEquals(80,first(restored))
    }
}
