package com.cruisetune.player.data.open

import com.cruisetune.player.core.UserError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class OpenSessionManager(private val store: OpenSessionStore, private val broker: OpenBroker, private val now: () -> Long = System::currentTimeMillis) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }
    suspend fun activate(session: OpenSession): OpenSession = withContext(Dispatchers.IO) {
        lock(session.accountId).withLock {
            validate(session)
            val next = session.copy(generation = (store.read(session.accountId)?.generation ?: -1) + 1)
            store.write(next)
            next
        }
    }
    suspend fun fresh(accountId: String): OpenSession = withContext(Dispatchers.IO) {
        lock(accountId).withLock {
            val current = load(accountId)
            if (current.expiresAtMs > 0 && current.expiresAtMs <= now() + 60000) rotate(current) else current
        }
    }
    suspend fun refreshAfterFailure(accountId: String, failedGeneration: Long): OpenSession = withContext(Dispatchers.IO) {
        lock(accountId).withLock {
            val current = load(accountId)
            if (current.generation != failedGeneration) current else rotate(current)
        }
    }
    suspend fun disconnect(accountId: String) = withContext(Dispatchers.IO) { lock(accountId).withLock { store.remove(accountId) } }
    suspend fun acceptServerToken(accountId: String, expectedGeneration: Long, token: String): OpenSession = withContext(Dispatchers.IO) {
        lock(accountId).withLock {
            val current = load(accountId)
            if (current.generation != expectedGeneration || current.accessToken == token) current
            else {
                val next = current.copy(accessToken = token, expiresAtMs = 0, generation = current.generation + 1)
                validate(next); store.write(next); next
            }
        }
    }
    private fun load(id: String): OpenSession = store.read(id) ?: throw UserError("开放平台账号需重新授权", true)
    private suspend fun rotate(current: OpenSession): OpenSession {
        val returned = broker.rotate(current)
        if (returned.accountId != current.accountId || returned.userId != current.userId || returned.clientId != current.clientId || returned.profileId != current.profileId || returned.deviceId != current.deviceId) throw UserError("授权服务返回了不匹配的账号，请重新授权", true)
        val next = returned.copy(generation = current.generation + 1)
        validate(next)
        if (next.expiresAtMs > 0 && next.expiresAtMs <= now()) throw UserError("授权服务未返回有效的新令牌", true)
        // Store the access/refresh pair atomically before any caller can consume it.
        store.write(next)
        return next
    }
    private fun validate(s: OpenSession) {
        if (s.userId.isBlank() || s.accountId != OpenSession.id(s.clientId, s.userId) || s.deviceId.isBlank() || s.accessToken.isBlank() || s.refreshToken.isBlank()) throw UserError("开放平台授权信息不完整", true)
        if (listOf(s.accessToken, s.clientToken, s.clientId).any { value -> value.any { it == '\r' || it == '\n' || it == ';' } }) throw UserError("开放平台授权信息格式不正确", true)
    }
}
