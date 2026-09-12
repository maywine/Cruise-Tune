package com.cruisetune.player.ui

enum class PlaybackAction(val label: String, val description: String) {
    RECONNECT("重新连接", "重新连接当前音乐来源"), RETRY("重试", "重试当前歌曲"),
    PAUSE("暂停", "暂停播放"), ADD("添加音乐", "添加音乐目录"),
    RESUME("继续", "继续播放"), PLAY("播放", "开始播放");

    companion object {
        fun choose(failed: Boolean, needsLogin: Boolean, playWhenReady: Boolean, empty: Boolean, hasPosition: Boolean) = when {
            failed -> if (needsLogin) RECONNECT else RETRY
            playWhenReady -> PAUSE
            empty -> ADD
            hasPosition -> RESUME
            else -> PLAY
        }
    }
}
