package com.cruisetune.player.core

import java.io.IOException

class UserError(message: String, val needsLogin: Boolean = false, val retryable: Boolean = false) : IOException(message)
class ConfirmedRemoteFileMissing : IOException("夸克文件已不可用")
class InvalidMediaRange : java.net.ProtocolException("下载分段信息不匹配，已停止读取以保护缓存")

/** Media3 wraps provider failures; retain the actionable, sanitized cause. */
fun userError(error: Throwable?): UserError? {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current = error
    while (current != null && seen.add(current)) {
        if (current is UserError) return current
        current = current.cause
    }
    return null
}

fun confirmedRemoteFileMissing(error: Throwable?): ConfirmedRemoteFileMissing? =
    generateSequence(error) { it.cause }.take(16).filterIsInstance<ConfirmedRemoteFileMissing>().firstOrNull()

fun readableError(error: Throwable): String = userError(error)?.message ?: confirmedRemoteFileMissing(error)?.message ?: when (error) {
    is InvalidMediaRange -> error.message ?: "下载分段信息不匹配"
    is UserError -> error.message ?: "暂时无法完成，请稍后重试"
    is java.net.UnknownHostException -> "暂无网络，已保留播放位置"
    is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
    is SecurityException -> "目录访问权限已失效，请重新选择目录"
    is java.io.EOFException -> "音频文件不完整或已损坏，请检查源文件"
    is java.io.IOException -> "读取失败，请检查网络或文件是否可用"
    else -> "暂时无法完成，请重试"
}
