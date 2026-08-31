package com.hybridinfer

import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Reference remote engine for any OpenAI-compatible endpoint (OpenAI, OpenRouter,
 * a local vLLM server, ...). Uses the JDK's HttpURLConnection (available on
 * Android) + SSE streaming, so it has no HTTP-client dependency. A read timeout
 * doubles as the stall watchdog. Hosts may substitute OkHttp/Retrofit.
 */
class OpenAiEngine(
    override val model: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val apiKey: String? = null,
    override val tier: String = "remote",
) : Engine {
    override val backend: String = "openai"

    override fun stream(
        messages: List<Message>,
        timeoutS: Double,
        stallTimeoutS: Double?,
    ): Sequence<String> = sequence {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = (timeoutS * 1000).toInt()
            readTimeout = ((stallTimeoutS ?: timeoutS) * 1000).toInt()
            setRequestProperty("Content-Type", "application/json")
            if (apiKey != null) setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            conn.outputStream.use { it.write(buildRequestJson(messages).toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            throw BackendException("connection", e.message ?: "")
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            throw BackendException("connection", e.message ?: "")
        }
        if (status != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            val code = when {
                status >= 500 -> "server_error"
                status == 401 || status == 403 -> "auth"
                else -> "http_$status"
            }
            throw BackendException(code, err.take(300))
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    throw BackendException("stall")
                } catch (e: Exception) {
                    throw BackendException("connection", e.message ?: "")
                } ?: break

                var l = line
                if (l.startsWith("data:")) l = l.substring(5).trim()
                if (l.isEmpty()) continue
                if (l == "[DONE]") break

                val content: String? = try {
                    val delta = JsonParser.parseString(l).asJsonObject
                        .getAsJsonArray("choices").get(0).asJsonObject
                        .getAsJsonObject("delta")
                    val ce = delta.get("content")
                    if (ce != null && !ce.isJsonNull) ce.asString else null
                } catch (e: Exception) {
                    null
                }
                if (!content.isNullOrEmpty()) yield(content)
            }
        } finally {
            reader.close()
            conn.disconnect()
        }
    }

    // Minimal, escaping-correct JSON for {model, stream:true, messages:[{role,content}]}.
    private fun buildRequestJson(messages: List<Message>): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(quote(model)).append(",\"stream\":true,\"messages\":[")
        messages.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{\"role\":").append(quote((m["role"] ?: "user").toString()))
                .append(",\"content\":").append(quote((m["content"] ?: "").toString())).append("}")
        }
        return sb.append("]}").toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
