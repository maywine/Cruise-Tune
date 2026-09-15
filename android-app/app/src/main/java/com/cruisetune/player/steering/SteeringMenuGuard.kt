package com.cruisetune.player.steering

import kotlinx.coroutines.CancellationException

/** The sample calls index 2 media and 3 phone. Unknown/failure never defaults to media. */
internal class SteeringMenuGuard(private val read: suspend () -> Int?, private val changed: (Int?) -> Unit) {
    var index: Int? = null; private set
    var revision = 0; private set
    private var initialized = false
    private var epoch = 0
    private var query = 0L
    private var applied = 0L
    fun reset() { epoch++; initialized = false; index = null; revision++; changed(null) }
    suspend fun refresh(): Boolean {
        val run = epoch; val request = ++query
        val value = try { read() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (run != epoch || request < applied) return false
        applied = request
        if (!initialized || value != index) {
            initialized = true; index = value; revision++; changed(value)
        }
        return value == 2
    }
    suspend fun permits(ticket: Int): Boolean = refresh() && revision == ticket
}
