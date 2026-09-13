package com.cruisetune.player.ui

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cruisetune.player.core.Track

/** Follow selection changes once, after the latest queue diff and any user gesture finish. */
internal class QueueFollower {
    private data class Target(val id: String, val position: Int)
    private var view: RecyclerView? = null
    private var adapter: TrackAdapter? = null
    private var enabled = false
    private var touched = false
    private var current: Target? = null
    private var pending: Target? = null
    private var expected: List<Track> = emptyList()
    private val follow = Runnable { followWhenReady() }
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if(newState==RecyclerView.SCROLL_STATE_IDLE)schedule()
        }
    }
    private val touchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touched=true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touched=false;schedule() }
            }
            return false
        }
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { schedule() }
        override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(follow);touched=false }
    }

    fun bind(recyclerView: RecyclerView, trackAdapter: TrackAdapter) {
        detach()
        view=recyclerView;adapter=trackAdapter
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnItemTouchListener(touchListener)
        recyclerView.addOnAttachStateChangeListener(attachListener)
        schedule()
    }
    fun detach() {
        view?.let {
            it.removeCallbacks(follow);it.removeOnScrollListener(scrollListener)
            it.removeOnItemTouchListener(touchListener);it.removeOnAttachStateChangeListener(attachListener)
        }
        view=null;adapter=null;touched=false
    }
    fun update(showingQueue: Boolean, currentId: String?, tracks: List<Track>) {
        val entering=showingQueue && !enabled
        enabled=showingQueue;expected=tracks
        if(!enabled) { current=null;pending=null;view?.removeCallbacks(follow);return }
        val position=tracks.indexOfFirst { it.id==currentId }
        val target=if(currentId!=null && position>=0)Target(currentId,position)else null
        if(entering || target!=current)pending=target
        current=target
        schedule()
    }
    fun revealCurrent() { if(enabled) { view?.stopScroll();pending=current;schedule() } }
    fun onListCommitted() { schedule() }
    private fun schedule() {
        val list=view ?: return
        list.removeCallbacks(follow)
        if(enabled && pending!=null)list.postOnAnimation(follow)
    }
    private fun followWhenReady() {
        val list=view ?: return
        val target=pending ?: return
        if(!enabled || touched || !list.isAttachedToWindow || !list.isShown || list.scrollState!=RecyclerView.SCROLL_STATE_IDLE)return
        val items=adapter?.currentList ?: return
        if(items!=expected)return
        if(list.isComputingLayout || list.hasPendingAdapterUpdates() || list.isLayoutRequested || !list.isLaidOut) {
            schedule();return
        }
        val layout=list.layoutManager as? LinearLayoutManager ?: return
        pending=null
        if(items.getOrNull(target.position)?.id!=target.id)return
        val first=layout.findFirstCompletelyVisibleItemPosition()
        val last=layout.findLastCompletelyVisibleItemPosition()
        if(first<0 || target.position !in first..last) {
            // Jump directly: long queue traversals are distracting and unnecessary in the player.
            layout.scrollToPositionWithOffset(target.position,0)
        }
    }
}
