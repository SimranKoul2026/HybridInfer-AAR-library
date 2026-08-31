package com.hybridinfer

/**
 * Prompt-complexity estimation. Mirrors the Python implementation and SPEC.md
 * section 2 exactly (round-half-up then floor) so both stay in lockstep.
 */
object Complexity {

    /** Rough token count without a tokenizer. Identical to the Python port. */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val chars = text.length
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val raw = maxOf(chars / 4.0, words * 0.75)
        return maxOf(1, (raw + 0.5).toInt())   // +0.5 then truncate = round half up
    }

    /** Flatten OpenAI-style messages (String content or content-part list) to text. */
    fun messagesText(messages: List<Map<String, Any?>>): String {
        val parts = ArrayList<String>()
        for (m in messages) {
            when (val c = m["content"]) {
                is String -> parts.add(c)
                is List<*> -> for (seg in c) {
                    if (seg is Map<*, *> && seg["type"] == "text") {
                        parts.add((seg["text"] ?: "").toString())
                    }
                }
            }
        }
        return parts.joinToString("\n")
    }

    fun promptTokens(messages: List<Map<String, Any?>>): Int =
        estimateTokens(messagesText(messages))

    /** 0 = short, 1 = medium, 2 = long. */
    fun complexityBin(
        messages: List<Map<String, Any?>>,
        shortMaxTokens: Int = 128,
        mediumMaxTokens: Int = 512,
    ): Int {
        val t = promptTokens(messages)
        return when {
            t < shortMaxTokens -> 0
            t < mediumMaxTokens -> 1
            else -> 2
        }
    }
}
