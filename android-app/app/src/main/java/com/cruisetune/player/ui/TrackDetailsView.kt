package com.cruisetune.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.view.animation.PathInterpolatorCompat
import com.cruisetune.player.R
import com.cruisetune.player.core.OfflineState
import com.cruisetune.player.ui.Design.dp
import kotlin.math.min

/** Artwork stays still; only newly selected artwork fades over the current presentation. */
internal class ArtworkSwitcher(context: Context) : FrameLayout(context) {
    private var trackId: String? = null
    private var bitmap: Bitmap? = null
    private var initialized = false
    private val easing = PathInterpolatorCompat.create(.23f, 1f, .32f, 1f)

    fun show(track: String?, value: Bitmap?) {
        if (initialized && trackId == track && bitmap === value) return
        val animate = initialized && isAttachedToWindow && isShown && !Design.reducedMotion(context)
        initialized = true; trackId = track; bitmap = value
        // Freezing existing layer alphas preserves the visible blend during rapid skipping.
        for (i in 0 until childCount) getChildAt(i).animate().cancel()
        val incoming = CoverView(context).apply { seed = track?.hashCode() ?: 0; artwork(value) }
        incoming.alpha = if (animate) 0f else 1f
        addView(incoming, LayoutParams(-1, -1))
        fun retireOldLayers() {
            while (childCount > 1 && getChildAt(0) !== incoming) removeViewAt(0)
        }
        if (animate) incoming.animate().alpha(1f).setDuration(150).setInterpolator(easing).withEndAction(::retireOldLayers).start()
        else retireOldLayers()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = min(context.dp(320), min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec)))
        super.onMeasure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
    }

    override fun onDetachedFromWindow() {
        for (i in 0 until childCount) getChildAt(i).animate().cancel()
        while (childCount > 1) removeViewAt(0)
        if (childCount == 1) getChildAt(0).alpha = 1f
        super.onDetachedFromWindow()
    }
}

internal class TrackDetailsView(context: Context, toggleLyrics: () -> Unit, keepOffline: () -> Unit) : LinearLayout(context) {
    private val artwork = ArtworkSwitcher(context).apply { id = R.id.player_artwork }
    private val lyrics = object:LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val lines=if(MeasureSpec.getSize(heightMeasureSpec) >= 2*(current.lineHeight+next.lineHeight)+context.dp(10))2 else 1
            if(current.maxLines!=lines)current.maxLines=lines
            if(next.maxLines!=lines)next.maxLines=lines
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        }
    }.apply { orientation = VERTICAL; gravity = Gravity.CENTER;visibility=GONE }
    private val current = Design.label(context, "", 24f, bold = true).apply {
        id=R.id.player_lyric_current;gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END
    }
    private val next = Design.label(context, "", 20f, Design.secondary).apply {
        id=R.id.player_lyric_next;gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END
    }
    private val plainText = Design.label(context, "", 20f).apply {
        id=R.id.player_lyric_plain;setLineSpacing(context.dp(4).toFloat(),1f)
    }
    private val plainScroll = ScrollView(context).apply {
        id=R.id.player_lyric_scroll;isFillViewport=true
        addView(plainText,LayoutParams(-1,-2))
    }
    private val plainLyrics = LinearLayout(context).apply {
        orientation=VERTICAL;visibility=GONE
        addView(Design.label(context,"未同步歌词 · 可手动滚动",16f,Design.secondary),LayoutParams(-1,-2).apply { bottomMargin=context.dp(6) })
        addView(plainScroll,LayoutParams(-1,0,1f))
    }
    private var plainKey: String? = null
    private val metadata = LinearLayout(context).apply { orientation=VERTICAL }
    private val artistLabel = Design.label(context, "", 20f).apply {
        id=R.id.player_artist;gravity=Gravity.CENTER;minLines=1;maxLines=1;ellipsize=TextUtils.TruncateAt.END;visibility=INVISIBLE
    }
    private val albumLabel = Design.label(context, "", 18f, Design.secondary).apply {
        id=R.id.player_album;gravity=Gravity.CENTER;minLines=1;maxLines=1;ellipsize=TextUtils.TruncateAt.END;visibility=INVISIBLE
    }
    private val mode = TouchButton(context, "歌词").apply {
        id=R.id.player_lyrics_toggle;textSize=18f;minHeight=context.dp(48);setPadding(context.dp(8),context.dp(6),context.dp(8),context.dp(6))
        isEnabled=false;setOnClickListener { toggleLyrics() }
    }
    private val offline = TouchButton(context, "保留离线").apply {
        id=R.id.player_offline;textSize=18f;minHeight=context.dp(48);setPadding(context.dp(8),context.dp(6),context.dp(8),context.dp(6))
        isEnabled=false;setOnClickListener { keepOffline() }
    }

    init {
        orientation=VERTICAL;id=R.id.player_details
        artwork.show(null,null)
        val content=FrameLayout(context)
        content.addView(artwork,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        lyrics.addView(current,LayoutParams(-1,-2))
        lyrics.addView(next,LayoutParams(-1,-2).apply { topMargin=context.dp(10) })
        content.addView(lyrics,FrameLayout.LayoutParams(-1,-1))
        content.addView(plainLyrics,FrameLayout.LayoutParams(-1,-1))
        addView(content,LayoutParams(-1,0,1f))
        metadata.addView(artistLabel,LayoutParams(-1,-2))
        metadata.addView(albumLabel,LayoutParams(-1,-2).apply { topMargin=context.dp(4) })
        addView(metadata,LayoutParams(-1,-2).apply { topMargin=context.dp(8);bottomMargin=context.dp(4) })
        val actions=LinearLayout(context)
        actions.addView(mode,LayoutParams(0,-2,1f))
        actions.addView(offline,LayoutParams(0,-2,1f).apply { marginStart=context.dp(8) })
        addView(actions,LayoutParams(-1,-2))
    }

    fun showArtwork(track: String?, bitmap: Bitmap?) = artwork.show(track, bitmap)

    fun update(artist: String?, album: String?, showingLyrics: Boolean, state: LyricsState,
        positionMs: Long, hasTrack: Boolean, offlineState: OfflineState, storageDescription: String) {
        artistLabel.setTextIfChanged(artist.orEmpty());albumLabel.setTextIfChanged(album.orEmpty())
        // Reserve both text rows while the audio tags load so artwork and lyrics stay anchored.
        artistLabel.visibility=if(artist.isNullOrBlank())INVISIBLE else VISIBLE
        albumLabel.visibility=if(album.isNullOrBlank())INVISIBLE else VISIBLE
        artwork.visibility=if(showingLyrics)GONE else VISIBLE
        val untimed = !state.lyrics?.plainText.isNullOrBlank()
        lyrics.visibility=if(showingLyrics && !untimed)VISIBLE else GONE
        plainLyrics.visibility=if(showingLyrics && untimed)VISIBLE else GONE
        mode.setTextIfChanged(if(showingLyrics) "封面" else "歌词")
        mode.contentDescription=if(showingLyrics) "切换到歌曲封面" else "切换到歌词"
        mode.isEnabled=hasTrack
        offline.setTextIfChanged(offlineState.label)
        offline.contentDescription="${offlineState.label}，$storageDescription"
        offline.isEnabled=hasTrack && offlineState.canDownload
        updateLyrics(state,positionMs)
    }

    fun updateLyrics(state: LyricsState, positionMs: Long) {
        if(plainLyrics.visibility==VISIBLE) {
            val value=state.lyrics?.plainText.orEmpty()
            if(plainKey!=state.key || !TextUtils.equals(plainText.text,value)) {
                plainKey=state.key;plainText.text=value;plainScroll.scrollTo(0,0)
            }
            return
        }
        if(lyrics.visibility!=VISIBLE)return
        val frame=state.lyrics?.at(positionMs)
        current.setTextIfChanged(frame?.current ?: state.message)
        next.setTextIfChanged(frame?.next.orEmpty())
    }

    private fun TextView.setTextIfChanged(value: CharSequence) { if(!TextUtils.equals(text,value))text=value }
}
