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

    fun complete(messages: List<Message>): GenerationResult {
        val bin = Complexity.complexityBin(messages, shortMaxTokens, mediumMaxTokens)
        val route = ArrayList<String>()

        if (preferLocal(bin) && local != null && localPermitted()) {
            health.onLocalStarted()
            val res = runEngine(local, messages, isLocal = true)
            route.add("local")
            health.recordResult(res.ok, res.error, res.latencyMs, res.ttftMs)
            risk.update(local.backend, local.model, bin, failed = !res.ok)
            if (res.ok) {
                state.onLocalSuccess()
                return res.copy(route = route.toList(), tier = "local")
            }
            state.onLocalFailure()
            health.onOffloaded()
            if (!config.enableInRequestFallback || remote == null) {
                return res.copy(route = route.toList())
            }
        }

        if (remote != null) {
            val res = runEngine(remote, messages, isLocal = false)
            route.add("remote")
            health.recordResult(res.ok, res.error, res.latencyMs, res.ttftMs)
            return res.copy(route = route.toList(), tier = "remote", fellBack = route.contains("local"))
        }

        if (local != null && !route.contains("local")) {
            health.onLocalStarted()
            val res = runEngine(local, messages, isLocal = true)
            route.add("local")
            risk.update(local.backend, local.model, bin, failed = !res.ok)
            health.recordResult(res.ok, res.error, res.latencyMs, res.ttftMs)
            if (res.ok) state.onLocalSuccess() else state.onLocalFailure()
            return res.copy(route = route.toList(), tier = "local")
        }

        return GenerationResult(ok = false, error = "no_backend_available", route = route.toList())
    }

    /** Streaming with first-token-commit fallback (SPEC.md section 7). */
    fun stream(messages: List<Message>): Sequence<StreamChunk> = sequence {
        val bin = Complexity.complexityBin(messages, shortMaxTokens, mediumMaxTokens)
        val route = ArrayList<String>()
        var triedLocal = false

        if (preferLocal(bin) && local != null && localPermitted()) {
            triedLocal = true
            route.add("local")
            health.onLocalStarted()
            var emitted = 0
            var preTokenError: String? = null
            var midError: String? = null
            try {
                for (delta in local.stream(messages, config.localTimeoutS, config.localStallTimeoutS)) {
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
                yield(StreamChunk(done = true, error = midError, meta = meta("local", route, false, emitted)))
                return@sequence
            }

            val code = preTokenError ?: "empty"
            risk.update(local.backend, local.model, bin, failed = true)
            health.recordResult(false, code, 0.0, null)
            state.onLocalFailure()
            health.onOffloaded()
            if (!config.enableInRequestFallback || remote == null) {
                yield(StreamChunk(done = true, error = code, meta = meta("local", route, false, 0)))
                return@sequence
            }
        }

        if (remote != null) {
            route.add("remote")
            var n = 0
            var err: String? = null
            try {
                for (delta in remote.stream(messages, config.remoteTimeoutS, null)) {
                    n += 1
                    yield(StreamChunk(delta = delta, tier = "remote", model = remote.model))
                }
            } catch (e: BackendException) {
                err = e.code
            }
            health.recordResult(err == null, err, 0.0, null)
            yield(StreamChunk(done = true, error = err, meta = meta("remote", route, triedLocal, n)))
            return@sequence
        }

        if (local != null && !triedLocal) {
            route.add("local")
            var n = 0
            var err: String? = null
            try {
                for (delta in local.stream(messages, config.localTimeoutS, config.localStallTimeoutS)) {
                    n += 1
                    yield(StreamChunk(delta = delta, tier = "local", model = local.model))
                }
            } catch (e: BackendException) {
                err = e.code
            }
            val ok = err == null && n > 0
            risk.update(local.backend, local.model, bin, failed = !ok)
            if (ok) state.onLocalSuccess() else state.onLocalFailure()
            yield(StreamChunk(done = true, error = err ?: if (ok) null else "empty", meta = meta("local", route, false, n)))
            return@sequence
        }

        yield(StreamChunk(done = true, error = "no_backend_available", meta = meta("", route, false, 0)))
    }

    private fun runEngine(engine: Engine, messages: List<Message>, isLocal: Boolean): GenerationResult {
        val start = System.nanoTime()
        var ttft: Double? = null
        val sb = StringBuilder()
        var n = 0
        val timeout = if (isLocal) config.localTimeoutS else config.remoteTimeoutS
        val stall = if (isLocal) config.localStallTimeoutS else null
        return try {
            for (delta in engine.stream(messages, timeout, stall)) {
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

    /** Public for conformance testing. See SPEC.md section 6. */
    fun preferLocal(bin: Int): Boolean {
        if (config.forceRemote) return false
        if (config.forceLocal) return true
        if (local == null) return false
        if (remote == null) return true
        if (config.enableRuntimeHealthGating) {
            val pfail = risk.prFail(local.backend, local.model, bin)
            state.onDecision(pfail)
            if (pfail >= config.riskPreferRemote) return false
        }
        return true
    }

    private fun localPermitted(): Boolean =
        if (!config.enableRecovery) true else state.localAllowed()

    private fun meta(tier: String, route: List<String>, fellBack: Boolean, n: Int): Map<String, Any?> =
        mapOf(
            "tier" to tier, "route" to route.toList(), "fell_back" to fellBack,
            "completion_tokens" to n, "state" to state.state.name,
        )
}
