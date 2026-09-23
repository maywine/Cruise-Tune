package com.cruisetune.player.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cruisetune.player.core.Track
import com.cruisetune.player.ui.Design.dp

class TrackAdapter(private val compact: Boolean = false, private val select: (Track) -> Unit) : ListAdapter<Track, TrackAdapter.Holder>(object : DiffUtil.ItemCallback<Track>() {
    override fun areItemsTheSame(a: Track, b: Track) = a.id == b.id
    override fun areContentsTheSame(a: Track, b: Track) = a == b
}) {
    var currentId: String? = null; private set
    private var playbackLabel = "已暂停"
    private var missingIds = emptySet<String>()
    fun updateMissing(ids: Set<String>) {
        if (ids == missingIds) return
        val changed = (missingIds union ids) - (missingIds intersect ids)
        missingIds = ids.toSet()
        changed.forEach { id -> currentList.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(::notifyItemChanged) }
    }
    fun updatePlayback(id: String?, state: String) {
        if (id == currentId && state == playbackLabel) return
        val affected = listOf(currentId, id).distinct().mapNotNull { key -> currentList.indexOfFirst { it.id == key }.takeIf { it >= 0 } }
        currentId = id; playbackLabel = state
        affected.forEach { notifyItemChanged(it) }
    }
    class Holder(val row: LinearLayout, val number: TextView, val title: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(row)
    override fun onCurrentListChanged(previousList: MutableList<Track>, currentList: MutableList<Track>) {
        super.onCurrentListChanged(previousList, currentList)
        // DiffUtil can move unchanged holders without rebinding their position-derived labels.
        if (currentList.isNotEmpty()) notifyItemRangeChanged(0, currentList.size, "positions")
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = c.dp(if (compact) 76 else 84); setPadding(c.dp(12), c.dp(if (compact) 8 else 12), c.dp(12), c.dp(if (compact) 8 else 12)); isClickable = true; isFocusable = true }
        val largeCompact = compact && c.resources.configuration.fontScale > 1.3f
        val number = Design.label(c, "", if (compact) 16f else 18f, Design.secondary).apply { gravity = Gravity.CENTER; setSingleLine(true) }
        val numberWidth = ((if (compact) 30 else 44) * c.resources.configuration.fontScale).toInt()
        row.addView(number, LinearLayout.LayoutParams(c.dp(numberWidth), -2))
        val content = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val title = Design.label(c, "", if (compact) 20f else 23f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val subtitle = Design.label(c, "", if (compact) 16f else 18f, Design.secondary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = if (largeCompact) android.view.View.GONE else android.view.View.VISIBLE }
        listOf(number, title, subtitle).forEach { it.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        content.addView(title); content.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(if (compact) 4 else 7) })
        row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = c.dp(8) }
        return Holder(row, number, title, subtitle)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = getItem(position); val selected = track.id == currentId
        val missing = track.id in missingIds
        holder.title.text = if (missing) "云端失效 · ${track.title}" else track.title
        holder.number.text = if (missing) "!" else if (selected) (if (playbackLabel == "正在播放") "▥" else "•") else (position + 1).toString().padStart(2, '0')
        holder.number.setTextColor(if (missing) Design.danger else if (selected) Design.accent else Design.secondary)
        val format = track.relativePath.substringAfterLast('.', "音频").uppercase()
        holder.subtitle.text = "${if (track.localUri.isNotEmpty()) "本地" else "夸克网盘"}  ·  $format"
        holder.row.background = Design.surface(if (selected) Design.raised else Design.panel, holder.row.context.dp(16).toFloat())
        val attrs = holder.row.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        holder.row.foreground = attrs.getDrawable(0); attrs.recycle()
        holder.row.contentDescription = "${track.title}，${holder.subtitle.text}${if (missing) "，云端文件已失效" else ""}${if (selected) "，当前歌曲，$playbackLabel" else ""}"
        holder.row.isSelected = selected
        holder.row.setOnClickListener { select(track) }
    }
}
