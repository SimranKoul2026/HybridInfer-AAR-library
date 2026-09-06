package com.hybridinfer

import java.io.File

/**
 * Self-calibrating per-(backend, model, length-bin) latency baseline.
 *
 * The stall/prefill watchdog thresholds must be relative to the device's own
 * measured cadence, not one absolute number for all hardware: an N tuned on a
 * fast device trips early on a slower one and sits there far too long on a fast
 * one that has genuinely wedged. This tracks an EWMA of time-to-first-token
 * (TTFT) and inter-token gap per (backend, model, length-bin) - keyed exactly
 * like [RiskProfile] - updated only on successful local runs, and derives
 * adaptive stall/prefill timeouts from it. Below [minSamples] observations it
 * falls back to the configured bootstrap default (cold start). Mirrors the Python
 * port and SPEC.md section 9; the calibration math is pinned by shared
 * conformance vectors. Persistence format is platform-specific (not part of
 * conformance): "key=ttft:gap:count;...".
 */
class LatencyProfile(
    private val path: String? = null,
    private val alpha: Double = 0.3,
    private val minSamples: Int = 5,
) {
    // key -> [ttftEwmaMs, gapEwmaMs, count]; -1.0 means "not yet observed".
    private val stats = HashMap<String, DoubleArray>()
    private val lock = Any()

    init { load() }

    private fun key(backend: String, model: String, bin: Int) = "$backend|$model|$bin"

    /**
     * Fold a successful run's measured TTFT + mean inter-token gap into the
     * baseline. [gapMs] is null when <2 tokens were produced (no gap to measure);
     * the TTFT baseline still updates.
     */
    fun observe(backend: String, model: String, bin: Int, ttftMs: Double?, gapMs: Double?) {
        val b = bin.coerceIn(0, 2)
        synchronized(lock) {
            val s = stats.getOrPut(key(backend, model, b)) { doubleArrayOf(-1.0, -1.0, 0.0) }
            if (ttftMs != null && ttftMs >= 0.0)
                s[0] = if (s[0] < 0.0) ttftMs else alpha * ttftMs + (1.0 - alpha) * s[0]
            if (gapMs != null && gapMs >= 0.0)
                s[1] = if (s[1] < 0.0) gapMs else alpha * gapMs + (1.0 - alpha) * s[1]
            s[2] = minOf(s[2] + 1.0, 10_000.0)
            persist()
        }
    }

    private fun baseline(backend: String, model: String, bin: Int): DoubleArray =
        stats[key(backend, model, bin.coerceIn(0, 2))] ?: doubleArrayOf(-1.0, -1.0, 0.0)

    /** Adaptive inter-token stall timeout = k x learned gap, clamped. Falls back to
     *  [defaultS] until there are >= minSamples runs with a measured gap. */
    fun stallTimeoutS(
        backend: String, model: String, bin: Int,
        defaultS: Double, ceilingS: Double, k: Double, floorS: Double,
    ): Double {
        val s = baseline(backend, model, bin)
        if (s[2] < minSamples || s[1] < 0.0) return defaultS
        return (k * s[1] / 1000.0).coerceIn(floorS, ceilingS)
    }

    /** Adaptive first-token timeout = k x learned TTFT, clamped. Falls back to
     *  [defaultS] until there are >= minSamples runs. */
    fun prefillTimeoutS(
        backend: String, model: String, bin: Int,
        defaultS: Double, ceilingS: Double, k: Double, floorS: Double,
    ): Double {
        val s = baseline(backend, model, bin)
        if (s[2] < minSamples || s[0] < 0.0) return defaultS
        return (k * s[0] / 1000.0).coerceIn(floorS, ceilingS)
    }

    fun snapshot(): Map<String, Triple<Double, Double, Int>> =
        stats.mapValues { Triple(it.value[0], it.value[1], it.value[2].toInt()) }

    private fun load() {
        val p = path ?: return
        val f = File(p)
        if (!f.exists()) return
        try {
            f.readText().split(";").filter { it.isNotBlank() }.forEach { entry ->
                val eq = entry.lastIndexOf('=')
                if (eq <= 0) return@forEach
                val k = entry.substring(0, eq)
                val parts = entry.substring(eq + 1).split(":")
                if (parts.size == 3) {
                    val t = parts[0].toDoubleOrNull() ?: return@forEach
                    val g = parts[1].toDoubleOrNull() ?: return@forEach
                    val c = parts[2].toDoubleOrNull() ?: return@forEach
                    stats[k] = doubleArrayOf(t, g, c)
                }
            }
        } catch (e: Exception) {
            // A corrupt profile must never crash the router; start empty.
        }
    }

    private fun persist() {
        val p = path ?: return
        try {
            File(p).writeText(
                stats.entries.joinToString(";") { "${it.key}=${it.value[0]}:${it.value[1]}:${it.value[2]}" }
            )
        } catch (e: Exception) {
            // best-effort
        }
    }
}
