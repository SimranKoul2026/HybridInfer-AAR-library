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
) {
    val controller = FailureAwareController(
        local = local,
        remote = remote,
        config = config,
        risk = RiskProfile(riskProfilePath),
        shortMaxTokens = shortMaxTokens,
        mediumMaxTokens = mediumMaxTokens,
    )

    fun complete(messages: List<Message>): GenerationResult = controller.complete(messages)

    fun stream(messages: List<Message>): Sequence<StreamChunk> = controller.stream(messages)

    fun state(): String = controller.state.state.name

    fun riskSnapshot(): Map<String, Pair<Int, Int>> = controller.risk.snapshot()
}
