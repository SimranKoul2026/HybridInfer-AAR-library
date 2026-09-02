package com.hybridinfer

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Shared conformance checker. Loads the language-neutral vectors (SPEC.md #10,
 * conformance/vectors.json - byte-identical to the Python repo) and returns a
 * list of human-readable failures (empty = all pass). Used by ConformanceTest
 * and by the standalone verification runner.
 */
object Conformance {

    private class DummyEngine(
        override val tier: String,
        override val backend: String,
        override val model: String,
    ) : Engine {
        override fun stream(
            messages: List<Message>,
            timeoutS: Double,
            stallTimeoutS: Double?,
            params: Map<String, Any?>?,
        ): Sequence<String> = emptySequence()
    }

    fun run(jsonText: String): List<String> {
        val root = JsonParser.parseString(jsonText).asJsonObject
        val failures = ArrayList<String>()
        checkComplexity(root.getAsJsonObject("complexity"), failures)
        checkRisk(root.getAsJsonObject("risk"), failures)
        checkState(root.getAsJsonObject("state"), failures)
        checkPreferLocal(root.getAsJsonObject("prefer_local"), failures)
        return failures
    }

    private fun checkComplexity(cx: JsonObject, failures: MutableList<String>) {
        val shortMax = cx.get("short_max_tokens").asInt
        val medMax = cx.get("medium_max_tokens").asInt
        for (el in cx.getAsJsonArray("cases")) {
            val c = el.asJsonObject
            val text = if (c.has("repeat")) c.get("repeat").asString.repeat(c.get("times").asInt)
            else c.get("text").asString
            val msgs = listOf(mapOf<String, Any?>("role" to "user", "content" to text))
            val tokens = Complexity.promptTokens(msgs)
            val bin = Complexity.complexityBin(msgs, shortMax, medMax)
            val expTok = c.get("expected_tokens").asInt
            val expBin = c.get("expected_bin").asInt
            if (tokens != expTok) failures.add("complexity tokens: got $tokens expected $expTok")
            if (bin != expBin) failures.add("complexity bin: got $bin expected $expBin")
        }
    }

    private fun checkRisk(rk: JsonObject, failures: MutableList<String>) {
        val tol = rk.get("tolerance").asDouble
        for (el in rk.getAsJsonArray("cases")) {
            val c = el.asJsonObject
            val bin = c.get("bin").asInt
            val attempts = c.get("attempts").asInt
            val fails = c.get("failures").asInt
            val rp = RiskProfile()
            repeat(fails) { rp.update("ollama", "m", bin, true) }
            repeat(attempts - fails) { rp.update("ollama", "m", bin, false) }
            val got = rp.prFail("ollama", "m", bin)
            val exp = c.get("expected_pfail").asDouble
            if (Math.abs(got - exp) >= tol)
                failures.add("risk pfail: got $got expected $exp (bin $bin a=$attempts f=$fails)")
        }
    }

    private fun checkState(st: JsonObject, failures: MutableList<String>) {
        val sp = st.getAsJsonObject("params")
        for (el in st.getAsJsonArray("cases")) {
            val case = el.asJsonObject
            val name = case.get("name").asString
            val clock = doubleArrayOf(0.0)
            val sm = SafetyStateMachine(
                cautionPfail = sp.get("caution_pfail").asDouble,
                unsafeFailures = sp.get("unsafe_failures").asInt,
                recoveryCooldownS = sp.get("recovery_cooldown_s").asDouble,
                recoveryBackoff = sp.get("recovery_backoff").asDouble,
                recoveryCooldownMaxS = sp.get("recovery_cooldown_max_s").asDouble,
                clock = { clock[0] },
            )
            for (sEl in case.getAsJsonArray("steps")) {
                val step = sEl.asJsonObject
                if (step.has("now")) clock[0] = step.get("now").asDouble
                when (step.get("op").asString) {
                    "on_decision" -> sm.onDecision(step.get("pfail").asDouble)
                    "on_local_failure" -> sm.onLocalFailure()
                    "on_local_success" -> sm.onLocalSuccess()
                    "local_allowed" -> {
                        val got = sm.localAllowed()
                        if (got != step.get("expect").asBoolean)
                            failures.add("state[$name] local_allowed: got $got expected ${step.get("expect").asBoolean}")
                    }
                }
                val expState = step.get("expect_state").asString
                if (sm.state.name != expState)
                    failures.add("state[$name]: got ${sm.state.name} expected $expState")
            }
        }
    }

    private fun checkPreferLocal(pl: JsonObject, failures: MutableList<String>) {
        for (el in pl.getAsJsonArray("cases")) {
            val c = el.asJsonObject
            val cfgObj = c.getAsJsonObject("config")
            fun boolOf(k: String, d: Boolean) = if (cfgObj.has(k)) cfgObj.get(k).asBoolean else d
            fun dblOf(k: String, d: Double) = if (cfgObj.has(k)) cfgObj.get(k).asDouble else d
            val cfg = ControllerConfig(
                forceLocal = boolOf("force_local", false),
                forceRemote = boolOf("force_remote", false),
                enableRuntimeHealthGating = boolOf("enable_runtime_health_gating", true),
                riskPreferRemote = dblOf("risk_prefer_remote", 0.6),
            )
            val rspec = c.getAsJsonObject("risk")
            val bin = rspec.get("bin").asInt
            val attempts = rspec.get("attempts").asInt
            val fails = rspec.get("failures").asInt
            val risk = RiskProfile()
            repeat(fails) { risk.update("ollama", "m", bin, true) }
            repeat(attempts - fails) { risk.update("ollama", "m", bin, false) }
            val ctrl = FailureAwareController(
                DummyEngine("local", "ollama", "m"),
                DummyEngine("remote", "openai", "r"),
                cfg, risk = risk,
            )
            val got = ctrl.preferLocal(c.get("bin").asInt)
            val exp = c.get("expected_prefer_local").asBoolean
            if (got != exp) failures.add("prefer_local: got $got expected $exp for $c")
        }
    }
}
