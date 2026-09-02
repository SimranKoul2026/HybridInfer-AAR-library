package com.hybridinfer

/**
 * Top-level entry point for host apps. Provide a local engine (e.g. MLC-LLM) and
 * a remote engine (OpenAI-compatible); HybridRouter wires them to the
 * failure-aware controller.
 *
 * ```
 * val router = HybridRouter(
 *     local = MlcEngine(...),                     // you implement Engine
 *     remote = OpenAiEngine(apiKey = ...),        // you implement Engine
 *     riskProfilePath = context.filesDir.resolve("hybridinfer_risk.txt").path,
 * )
 * val result = router.complete(listOf(mapOf("role" to "user", "content" to prompt)))
 * // or: router.stream(messages).forEach { chunk -> ... }
 * ```
 */
class HybridRouter(
    local: Engine?,
    remote: Engine?,
    config: ControllerConfig = ControllerConfig(),
    riskProfilePath: String? = null,
    shortMaxTokens: Int = 128,
    mediumMaxTokens: Int = 512,
    recoveryCooldownS: Double = 60.0,
    recoveryBackoff: Double = 2.0,
    recoveryCooldownMaxS: Double = 600.0,
) {
    val controller = FailureAwareController(
        local = local,
        remote = remote,
        config = config,
        risk = RiskProfile(riskProfilePath),
        state = SafetyStateMachine(
            recoveryCooldownS = recoveryCooldownS,
            recoveryBackoff = recoveryBackoff,
            recoveryCooldownMaxS = recoveryCooldownMaxS,
        ),
        shortMaxTokens = shortMaxTokens,
        mediumMaxTokens = mediumMaxTokens,
    )

    /**
     * @param safeToRetry set false for a side-effecting request that must not be
     *   replayed on fallback; supply [idempotencyKey] to re-enable fallback.
     */
    fun complete(
        messages: List<Message>,
        idempotencyKey: String? = null,
        safeToRetry: Boolean = true,
        params: Map<String, Any?>? = null,
    ): GenerationResult = controller.complete(messages, idempotencyKey, safeToRetry, params)

    fun stream(
        messages: List<Message>,
        idempotencyKey: String? = null,
        safeToRetry: Boolean = true,
        params: Map<String, Any?>? = null,
    ): Sequence<StreamChunk> = controller.stream(messages, idempotencyKey, safeToRetry, params)

    fun state(): String = controller.state.state.name

    fun riskSnapshot(): Map<String, Pair<Int, Int>> = controller.risk.snapshot()
}
