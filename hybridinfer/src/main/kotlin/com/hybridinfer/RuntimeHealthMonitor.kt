package com.hybridinfer

import java.util.ArrayDeque

data class HealthSnapshot(
    val recentFailures: Int,
    val recentTimeouts: Int,
    val lastLatencyMs: Double?,
    val lastTtftMs: Double?,
    val consecutiveLocal: Int,
)

/** Rolling runtime-health signals (SPEC.md section 5). */
class RuntimeHealthMonitor(
    private val windowS: Double = 300.0,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
) {
    private val failures = ArrayDeque<Double>()
    private val timeouts = ArrayDeque<Double>()
    private var lastLatency: Double? = null
    private var lastTtft: Double? = null
    private var consecLocal = 0

    private fun prune() {
        val cutoff = clock() - windowS
        while (failures.isNotEmpty() && failures.peekFirst() < cutoff) failures.removeFirst()
        while (timeouts.isNotEmpty() && timeouts.peekFirst() < cutoff) timeouts.removeFirst()
    }

    fun recordResult(ok: Boolean, error: String?, latencyMs: Double, ttftMs: Double?) {
        val now = clock()
        lastLatency = latencyMs
        if (ttftMs != null) lastTtft = ttftMs
        if (!ok) {
            failures.addLast(now)
            if (error == "timeout" || error == "prefill_timeout" || error == "stall") timeouts.addLast(now)
        }
        prune()
    }

    fun onLocalStarted() { consecLocal += 1 }
    fun onOffloaded() { consecLocal = 0 }

    fun snapshot(): HealthSnapshot {
        prune()
        return HealthSnapshot(failures.size, timeouts.size, lastLatency, lastTtft, consecLocal)
    }
}
