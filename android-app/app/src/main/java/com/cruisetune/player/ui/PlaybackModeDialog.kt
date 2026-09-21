package com.cruisetune.player.ui

import android.content.Context
import android.content.res.ColorStateList
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.Player
import com.cruisetune.player.ui.Design.dp

object PlaybackModes {
    val modes = listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)
    fun queueLoopEnabled(mode: Int) = mode == Player.REPEAT_MODE_ALL
    fun toggleQueueLoop(mode: Int) = if (queueLoopEnabled(mode)) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
    fun label(mode: Int) = when(mode) {
        Player.REPEAT_MODE_ONE -> "单曲循环"
        Player.REPEAT_MODE_ALL -> "列表循环"
        else -> "顺序播放"
    }
    fun show(context: Context, current: Int, select: (Int) -> Unit): AlertDialog {
        val group = RadioGroup(context).apply { setPadding(context.dp(20),0,context.dp(20),0) }
        val scroll = ScrollView(context).apply { addView(group) }
        val title = Design.label(context,"播放顺序",24f,bold=true).apply { setPadding(context.dp(24),context.dp(20),context.dp(24),context.dp(12)) }
        val dialog = AlertDialog.Builder(context).setCustomTitle(title).setView(scroll).setNegativeButton("取消",null).create()
        modes.forEach { mode ->
            group.addView(RadioButton(context).apply {
                id = android.view.View.generateViewId(); text = label(mode); textSize = 21f; minHeight = context.dp(76)
                setTextColor(Design.text); isChecked = current == mode
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Design.accent,Design.secondary))
                setOnClickListener { select(mode); dialog.dismiss() }
            }, RadioGroup.LayoutParams(-1,-2))
        }
        dialog.show(); Design.styleDialog(dialog,context)
        return dialog
    }
}
