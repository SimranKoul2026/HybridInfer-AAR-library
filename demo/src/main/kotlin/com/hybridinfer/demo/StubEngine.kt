package com.hybridinfer.demo

import com.hybridinfer.BackendException
import com.hybridinfer.Engine
import com.hybridinfer.Message

/**
 * A fake engine so we can exercise the AAR's routing/fallback on real hardware
 * without shipping a model. Set [fail] to an error code (e.g. "stall") to make it
 * fail before emitting a token - simulating an on-device wedge.
 */
class StubEngine(
    override val tier: String,
    override val backend: String,
    override val model: String,
    private val text: String,
) : Engine {

    @Volatile
    var fail: String? = null

    override fun stream(
        messages: List<Message>,
        timeoutS: Double,
        stallTimeoutS: Double?,
        params: Map<String, Any?>?,
    ): Sequence<String> = sequence {
        fail?.let { throw BackendException(it) }
        yield(text)
    }
}
