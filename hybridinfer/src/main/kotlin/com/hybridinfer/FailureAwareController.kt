package com.hybridinfer

/** Feature flags mirror the research A0-A3 ablation arms. See SPEC.md section 8. */
data class ControllerConfig(
    val localTimeoutS: Double = 120.0,
    val localStallTimeoutS: Double = 20.0,
    val remoteTimeoutS: Double = 120.0,
    val riskPreferRemote: Double = 0.6,
    val enableRuntimeHealthGating: Boolean = true,
    val enableInRequestFallback: Boolean = true,
    val enableRecovery: Boolean = true,
    val forceLocal: Boolean = false,
    val forceRemote: Boolean = false,
)

/**
 * Failure-aware runtime-health controller (SPEC.md sections 6-7). Decides local
 * vs remote, runs local under a timeout + stall watchdog, and on any local
 * failure transparently falls back to remote. Records outcomes to self-calibrate
 * the risk profile and drive the safety state machine.
 *
 * Per-request idempotency (v0.2): pass `safeToRetry = false` for a side-effecting
 * request. With no `idempotencyKey`, the controller commits to a single tier and
 * will NOT fall back after a local failure - so a mutating op is never replayed.
 * Supplying an idempotency key re-enables fallback.
 */
class FailureAwareController(
    private val local: Engine?,
    private val remote: Engine?,
    private val config: ControllerConfig = ControllerConfig(),
    val risk: RiskProfile = RiskProfile(),
    private val health: RuntimeHealthMonitor = RuntimeHealthMonitor(),
    val state: SafetyStateMachine = SafetyStateMachine(),
    private val shortMaxTokens: Int = 128,
    private val mediumMaxTokens: Int = 512,
) {

    private fun fallbackAllowed(safeToRetry: Boolean, key: String?): Boolean =
        config.enableInRequestFallback && (safeToRetry || key != null)

    private fun noFallbackReason(localErr: String): String = when {
        remote == null -> "local_failed:$localErr"
        !config.enableInRequestFallback -> "fallback_disabled:$localErr"
        else -> "no_fallback_unsafe:$localErr"
    }

    fun complete(
        messages: List<Message>,
        idempotencyKey: String? = null,
        safeToRetry: Boolean = true,
        params: Map<String, Any?>? = null,
    ): GenerationResult {
        val bin = Complexity.complexityBin(messages, shortMaxTokens, mediumMaxTokens)
        val route = ArrayList<String>()
        val allowFallback = fallbackAllowed(safeToRetry, idempotencyKey)
        val (prefer, reason0) = decide(bin)
        var reason = reason0

        if (prefer && local != null) {
            if (localPermitted()) {
                health.onLocalStarted()
                val res = runEngine(local, messages, isLocal = true, params = params)
                route.add("local")
                health.recordResult(res.ok, res.error, res.latencyMs, res.ttftMs)
                risk.update(local.backend, local.model, bin, failed = !res.ok)

                if (res.ok) {
                    state.onLocalSuccess()
                    return res.copy(route = route.toList(), tier = "local", reason = "local_ok", idempotencyKey = idempotencyKey)
                }
                state.onLocalFailure()
                health.onOffloaded()
                val localErr = res.error ?: "unknown"
                if (!allowFallback || remote == null) {
                    return res.copy(route = route.toList(), tier = "local",
                        reason = noFallbackReason(localErr), idempotencyKey = idempotencyKey)
                }
                reason = "fell_back:$localErr"
            } else {
                reason = "local_held_out"
            }
        }

        if (remote != null) {
            val res = runEngine(remote, messages, isLocal = false, params = params)
            route.add("remote")
            health.recordResult(res.ok, res.error, res.latencyMs, res.ttftMs)
            return res.copy(route = route.toList(), tier = "remote",
                fellBack = route.contains("local"), reason = reason, idempotencyKey = idempotencyKey)
        }

        if (local != null && !route.contains("local")) {
            health.onLocalStarted()
            val res = runEngine(local, messages, isLocal = true, params = params)
            route.add("local")
            risk.update(local.backend, local.model, bin, failed = !res.ok)
            health.recordResult(res.ok, res.error, res.latencyMs, res.ttftMs)
            val r: String
            if (res.ok) {
                state.onLocalSuccess(); r = "local_only"
            } else {
                state.onLocalFailure(); r = "local_failed:" + (res.error ?: "unknown")
            }
            return res.copy(route = route.toList(), tier = "local", reason = r, idempotencyKey = idempotencyKey)
        }

        return GenerationResult(ok = false, error = "no_backend_available",
            route = route.toList(), reason = "no_backend", idempotencyKey = idempotencyKey)
    }

    /** Streaming with first-token-commit fallback + idempotency (SPEC.md section 7). */
    fun stream(
        messages: List<Message>,
        idempotencyKey: String? = null,
        safeToRetry: Boolean = true,
        params: Map<String, Any?>? = null,
    ): Sequence<StreamChunk> = sequence {
        val bin = Complexity.complexityBin(messages, shortMaxTokens, mediumMaxTokens)
        val route = ArrayList<String>()
        var triedLocal = false
        val allowFallback = fallbackAllowed(safeToRetry, idempotencyKey)
        val (prefer, reason0) = decide(bin)
        var reason = reason0

        if (prefer && local != null) {
            if (localPermitted()) {
                triedLocal = true
                route.add("local")
                health.onLocalStarted()
                var emitted = 0
                var preTokenError: String? = null
                var midError: String? = null
                try {
                    for (delta in local.stream(messages, config.localTimeoutS, config.localStallTimeoutS, params)) {
                        emitted += 1
                        yield(StreamChunk(delta = delta, tier = "local", model = local.model))
                    }
                } catch (e: BackendException) {
                    if (emitted > 0) midError = e.code else preTokenError = e.code
                }

                if (emitted > 0) {
                    val ok = midError == null
                    risk.update(local.backend, local.model, bin, failed = !ok)
                    health.recordResult(ok, midError, 0.0, null)
                    if (ok) state.onLocalSuccess() else state.onLocalFailure()
                    val r = if (ok) "local_ok" else "local_committed_failed:" + (midError ?: "unknown")
                    yield(StreamChunk(done = true, error = midError,
                        meta = meta("local", route, false, emitted, r, idempotencyKey)))
                    return@sequence
                }

                val code = preTokenError ?: "empty"
                risk.update(local.backend, local.model, bin, failed = true)
                health.recordResult(false, code, 0.0, null)
                state.onLocalFailure()
                health.onOffloaded()
                if (!allowFallback || remote == null) {
                    yield(StreamChunk(done = true, error = code,
                        meta = meta("local", route, false, 0, noFallbackReason(code), idempotencyKey)))
                    return@sequence
                }
                reason = "fell_back:$code"
            } else {
                reason = "local_held_out"
            }
        }

        if (remote != null) {
            route.add("remote")
            var n = 0
            var err: String? = null
            try {
                for (delta in remote.stream(messages, config.remoteTimeoutS, null, params)) {
                    n += 1
                    yield(StreamChunk(delta = delta, tier = "remote", model = remote.model))
                }
            } catch (e: BackendException) {
                err = e.code
            }
            health.recordResult(err == null, err, 0.0, null)
            yield(StreamChunk(done = true, error = err,
                meta = meta("remote", route, triedLocal, n, reason, idempotencyKey)))
            return@sequence
        }

        if (local != null && !triedLocal) {
            route.add("local")
            var n = 0
            var err: String? = null
            try {
                for (delta in local.stream(messages, config.localTimeoutS, config.localStallTimeoutS, params)) {
                    n += 1
                    yield(StreamChunk(delta = delta, tier = "local", model = local.model))
                }
            } catch (e: BackendException) {
                err = e.code
            }
            val ok = err == null && n > 0
            risk.update(local.backend, local.model, bin, failed = !ok)
            val r: String
            if (ok) {
                state.onLocalSuccess(); r = "local_only"
            } else {
                state.onLocalFailure(); r = "local_failed:" + (err ?: "empty")
            }
            yield(StreamChunk(done = true, error = if (err != null) err else (if (ok) null else "empty"),
                meta = meta("local", route, false, n, r, idempotencyKey)))
            return@sequence
        }

        yield(StreamChunk(done = true, error = "no_backend_available",
            meta = meta("", route, false, 0, "no_backend", idempotencyKey)))
    }

    private fun runEngine(
        engine: Engine, messages: List<Message>, isLocal: Boolean, params: Map<String, Any?>?,
    ): GenerationResult {
        val start = System.nanoTime()
        var ttft: Double? = null
        val sb = StringBuilder()
        var n = 0
        val timeout = if (isLocal) config.localTimeoutS else config.remoteTimeoutS
        val stall = if (isLocal) config.localStallTimeoutS else null
        return try {
            for (delta in engine.stream(messages, timeout, stall, params)) {
                if (ttft == null) ttft = (System.nanoTime() - start) / 1e6
                n += 1
                sb.append(delta)
            }
            GenerationResult(
                text = sb.toString(), ok = true, tier = engine.tier, backend = engine.backend,
                model = engine.model, ttftMs = ttft, latencyMs = (System.nanoTime() - start) / 1e6,
                promptTokens = Complexity.promptTokens(messages), completionTokens = n,
            )
        } catch (e: BackendException) {
            GenerationResult(
                text = sb.toString(), ok = false, error = e.code, tier = engine.tier,
                backend = engine.backend, model = engine.model, ttftMs = ttft,
                latencyMs = (System.nanoTime() - start) / 1e6,
                promptTokens = Complexity.promptTokens(messages), completionTokens = n,
            )
        }
    }

    /** Up-front tier decision + a structured reason. See SPEC.md section 6. */
    fun decide(bin: Int): Pair<Boolean, String> {
        if (config.forceRemote) return false to "forced_remote"
        if (config.forceLocal) return true to "forced_local"
        if (local == null) return false to "no_local_backend"
        if (remote == null) return true to "local_only"
        if (config.enableRuntimeHealthGating) {
            val pfail = risk.prFail(local.backend, local.model, bin)
            state.onDecision(pfail)
            if (pfail >= config.riskPreferRemote) return false to "risk_gate"
        }
        return true to "local_first"
    }

    /** Kept for conformance-vector compatibility; see decide(). */
    fun preferLocal(bin: Int): Boolean = decide(bin).first

    private fun localPermitted(): Boolean =
        if (!config.enableRecovery) true else state.localAllowed()

    private fun meta(
        tier: String, route: List<String>, fellBack: Boolean, n: Int,
        reason: String, idempotencyKey: String?,
    ): Map<String, Any?> =
        mapOf(
            "tier" to tier, "route" to route.toList(), "fell_back" to fellBack,
            "completion_tokens" to n, "state" to state.state.name,
            "reason" to reason, "idempotency_key" to idempotencyKey,
        )
}
