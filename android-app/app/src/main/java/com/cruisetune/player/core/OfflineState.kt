package com.cruisetune.player.core

enum class OfflineState(val label: String, val canDownload: Boolean) {
    AVAILABLE("保留离线", true),
    QUEUED("等待下载", false),
    DOWNLOADING("下载中", false),
    SAVED("已保留", false),
    LOCAL("本地音乐", false),
    PAUSED("继续下载", true),
    FAILED("重试下载", true),
    REMOVING("正在移除", false),
    UNAVAILABLE("暂不可用", false)
}

data class StorageStatus(val text: String, val offline: OfflineState)
