/*
 * MlcEngine - reference on-device Engine backed by MLC-LLM.
 *
 * WHERE THIS GOES: copy it into YOUR APP module, not the library. It references
 * `ai.mlc.mlcllm.*` (the mlc4j module you build with MLC-LLM) and kotlinx
 * coroutines, neither of which the library depends on - so it is deliberately
 * NOT part of the library's compiled sources (it lives under examples/).
 *
 * APP-MODULE DEPENDENCIES:
 *   implementation(project(":mlc4j"))                                   // your MLC-LLM build
 *   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android")  // for the bridge below
 *   implementation("com.hybridinfer:router:0.1.0")                      // this library
 *
 * modelPath = on-device dir with the weights (params_shard_*.bin, ndarray-cache.json,
 *             mlc-chat-config.json, tokenizer). Push with adb.
 * modelLib  = compiled model-library id from mlc-app-config.json
 *             (e.g. "llama_q4f16_0_a95057c5d1a1dbce93918762c0a54907").
 *
 * The MLC API used here matches the research MlcOnDeviceEngine. Adjust for your
 * mlc4j version if its signatures differ. NOTE: this file is a reference and has
 * not been compiled in this repo (it needs mlc4j + the Android SDK).
 */
package com.hybridinfer.examples

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionRole
import com.hybridinfer.BackendException
import com.hybridinfer.Engine
import com.hybridinfer.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MlcEngine(
    private val modelPath: String,
    private val modelLib: String,
    override val model: String,
    override val tier: String = "local",
) : Engine {

    override val backend: String = "mlc"

    private val engine = MLCEngine()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (!loaded) {
            engine.reload(modelPath, modelLib)
            loaded = true
        }
    }

    private sealed interface Item {
        data class Token(val text: String) : Item
        object Done : Item
        data class Error(val code: String) : Item
    }

    /**
     * Bridges MLC's suspending channel to the library's synchronous Sequence.
     * A background coroutine collects tokens into a blocking queue; the Sequence
     * polls with `stallTimeoutS` (no token in that window => "stall", i.e. a
     * token-rate collapse / wedge) and enforces the overall `timeoutS`.
     */
    override fun stream(
        messages: List<Message>,
        timeoutS: Double,
        stallTimeoutS: Double?,
    ): Sequence<String> = sequence {
        val queue = LinkedBlockingQueue<Item>()
        val cancelled = AtomicBoolean(false)

        val job = scope.launch {
            try {
                ensureLoaded()
                val channel = engine.chat.completions.create(messages = convert(messages))
                for (response in channel) {
                    if (cancelled.get()) break
                    for (choice in response.choices) {
                        val piece = choice.delta.content?.asText().orEmpty()
                        if (piece.isNotEmpty()) queue.put(Item.Token(piece))
                    }
                }
                queue.put(Item.Done)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                val msg = (t.message ?: "").lowercase()
                val code = if ("memory" in msg || "alloc" in msg || "oom" in msg) "oom" else "server_error"
                queue.put(Item.Error(code))
            }
        }

        val start = System.nanoTime()
        val stallMs = ((stallTimeoutS ?: timeoutS) * 1000).toLong()
        var emitted = 0
        try {
            while (true) {
                if ((System.nanoTime() - start) / 1e9 > timeoutS) {
                    cancelled.set(true)
                    throw BackendException("timeout")
                }
                when (val item = queue.poll(stallMs, TimeUnit.MILLISECONDS)) {
                    null -> {
                        cancelled.set(true)
                        // 0 tokens -> first token never came (prefill/TTFT timeout);
                        // otherwise tokens stopped mid-stream (inter-token stall).
                        throw BackendException(if (emitted == 0) "prefill_timeout" else "stall")
                    }
                    is Item.Token -> { emitted += 1; yield(item.text) }
                    Item.Done -> return@sequence
                    is Item.Error -> throw BackendException(item.code)
                }
            }
        } finally {
            cancelled.set(true)
            job.cancel()
        }
    }

    /**
     * Hard recovery: unload + reload the native runtime to clear a wedged state.
     * Call this before a recovery probe when local keeps failing - a true native
     * GPU/OpenCL wedge may ignore cooperative cancellation, and only a reload
     * reclaims the runtime.
     */
    fun reset() {
        runCatching { engine.unload() }
        loaded = false
        ensureLoaded()
    }

    /** Release the runtime and background scope. */
    fun close() {
        scope.cancel()
        runCatching { engine.unload() }
        loaded = false
    }

    private fun convert(messages: List<Message>): List<ChatCompletionMessage> =
        messages.map { m ->
            val role = when ((m["role"] ?: "user").toString()) {
                "system" -> ChatCompletionRole.system
                "assistant" -> ChatCompletionRole.assistant
                else -> ChatCompletionRole.user
            }
            ChatCompletionMessage(role, (m["content"] ?: "").toString())
        }
}
