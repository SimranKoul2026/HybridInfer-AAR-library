package com.hybridinfer

/** OpenAI-style chat message. */
typealias Message = Map<String, Any?>

/** Uniform result the controller reasons about. */
data class GenerationResult(
    val text: String = "",
    val ok: Boolean = false,
    val error: String? = null,       // null on success; else an error code
    val tier: String = "",           // "local" | "remote"
    val backend: String = "",        // "mlc" | "ollama" | "openai" | ...
    val model: String = "",
    val ttftMs: Double? = null,
    val latencyMs: Double = 0.0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val fellBack: Boolean = false,
    val route: List<String> = emptyList(),
    val reason: String = "",              // structured routing reason (see controller)
    val idempotencyKey: String? = null,   // echoed back if the caller supplied one
)

/** One event from FailureAwareController.stream(). */
data class StreamChunk(
    val delta: String = "",
    val tier: String = "",
    val model: String = "",
    val done: Boolean = false,
    val error: String? = null,
    val meta: Map<String, Any?>? = null,
)

/**
 * Thrown by [Engine.stream] on failure. [code] is one of
 * timeout, stall, connection, oom, server_error, empty, or http_<code>.
 */
class BackendException(val code: String, val detail: String = "") : Exception(code)

/**
 * A single inference tier. The host app supplies implementations - e.g. an
 * MLC-LLM local engine and an OpenAI-compatible remote engine. [stream] yields
 * text deltas and throws [BackendException] on failure; implementations enforce
 * their own timeout and (for local streaming) stall detection.
 */
interface Engine {
    val tier: String
    val backend: String
    val model: String

    fun stream(
        messages: List<Message>,
        timeoutS: Double,
        stallTimeoutS: Double?,
        params: Map<String, Any?>? = null,   // OpenAI-style generation params to forward
    ): Sequence<String>

    fun available(): Boolean = true
}
