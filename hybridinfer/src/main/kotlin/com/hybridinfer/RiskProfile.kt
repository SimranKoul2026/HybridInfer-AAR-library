package com.hybridinfer

import java.io.File

/**
 * Self-calibrating per-(backend, model, length-bin) failure-risk profile.
 * Blends a prior with observed outcomes (Beta-style). Mirrors the Python port
 * and SPEC.md section 3 exactly. Persistence format is platform-specific (not
 * part of conformance): "key=attempts:failures;...".
 */
class RiskProfile(private val path: String? = null) {

    private val priorFail = doubleArrayOf(0.02, 0.10, 0.55)  // [short, medium, long]
    private val priorWeight = 8.0
    private val stats = HashMap<String, IntArray>()          // key -> [attempts, failures]
    private val lock = Any()

    init { load() }

    private fun key(backend: String, model: String, bin: Int) = "$backend|$model|$bin"

    fun prFail(backend: String, model: String, bin: Int): Double {
        val b = bin.coerceIn(0, 2)
        val s = stats[key(backend, model, b)] ?: intArrayOf(0, 0)
        val num = priorFail[b] * priorWeight + s[1]
        val den = priorWeight + s[0]
        val v = if (den != 0.0) num / den else priorFail[b]
        return v.coerceIn(0.0, 1.0)
    }

    fun update(backend: String, model: String, bin: Int, failed: Boolean) {
        val b = bin.coerceIn(0, 2)
        synchronized(lock) {
            val s = stats.getOrPut(key(backend, model, b)) { intArrayOf(0, 0) }
            s[0] = minOf(s[0] + 1, 10_000)
            if (failed) s[1] = minOf(s[1] + 1, 10_000)
            persist()
        }
    }

    fun snapshot(): Map<String, Pair<Int, Int>> =
        stats.mapValues { it.value[0] to it.value[1] }

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
                if (parts.size == 2) {
                    val a = parts[0].toIntOrNull() ?: return@forEach
                    val fl = parts[1].toIntOrNull() ?: return@forEach
                    stats[k] = intArrayOf(a, fl)
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
                stats.entries.joinToString(";") { "${it.key}=${it.value[0]}:${it.value[1]}" }
            )
        } catch (e: Exception) {
            // best-effort
        }
    }
}
