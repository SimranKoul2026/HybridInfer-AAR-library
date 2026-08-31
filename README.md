# HybridInfer (Android AAR)

**The HybridInfer reliability router for Android apps.** Run each LLM request on
an **on-device** model first, and automatically fall back to a **remote** model
the moment on-device inference stalls, crashes, or is *predicted* to fail. This
is the Android/Kotlin twin of the [`hybridinfer` Python tool](https://github.com/SimranKoul2026/HybridInfer-Python-tool) -
**one design, two implementations** (see [SPEC.md](SPEC.md)).

It's a **library**, not an app: you supply the engines (an MLC-LLM on-device
`Engine`, an OpenAI-compatible remote `Engine`), and HybridInfer supplies the
routing + reliability brain.

## What you get

- **Local-first routing** with a self-calibrating per-model failure-risk profile.
- **In-request fallback**: local stalls/OOMs/errors -> remote, transparently.
- **Safety state machine** (`LOCAL_ELIGIBLE -> CAUTION -> UNSAFE -> RECOVERING -> RESTORED`)
  that pulls a wedging model out of rotation and probes it back after a cooldown.
- **Streaming** with first-token-commit fallback.
- **Optional thermal signal** (`ThermalSignal`) - the input a desktop can't have.

## Install

Available via [JitPack](https://jitpack.io/#SimranKoul2026/HybridInfer-AAR-library):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven("https://jitpack.io") }
}
// build.gradle.kts
dependencies {
    implementation("com.github.SimranKoul2026:HybridInfer-AAR-library:v0.1.0")
}
```

## Usage

Implement `Engine` for your on-device model (MLC-LLM), use the bundled
`OpenAiEngine` for the remote tier, and route:

```kotlin
import com.hybridinfer.*

// 1. Your on-device engine wraps MLC-LLM. A complete, ready-to-adapt reference
//    (coroutine->Sequence bridge + stall watchdog + reset) is in examples/MlcEngine.kt.
class MlcEngine(override val model: String) : Engine {
    override val tier = "local"
    override val backend = "mlc"
    override fun stream(messages: List<Message>, timeoutS: Double, stallTimeoutS: Double?): Sequence<String> = sequence {
        // ... stream tokens from MLCEngine; throw BackendException("stall") on a wedge.
    }
}

// 2. Build the router:
val router = HybridRouter(
    local  = MlcEngine(model = "Llama-3.2-3B-Instruct-q4f16_1"),
    remote = OpenAiEngine(model = "gpt-4o-mini", apiKey = BuildConfig.OPENAI_KEY),
    riskProfilePath = context.filesDir.resolve("hybridinfer_risk.txt").path,
)

// 3. Non-streaming:
val res = router.complete(listOf(mapOf("role" to "user", "content" to prompt)))
println("${res.text}  [tier=${res.tier} fellBack=${res.fellBack}]")

// 4. Streaming (first-token-commit fallback):
router.stream(listOf(mapOf("role" to "user", "content" to prompt))).forEach { ch ->
    if (!ch.done) appendToUi(ch.delta) else logRouting(ch.meta)
}
```

### Fold in thermal (optional)

```kotlin
val hot = com.hybridinfer.android.ThermalSignal.shouldAvoidLocal(context)
val config = ControllerConfig(forceRemote = hot)   // skip local when the phone is hot
```

## One design, two implementations

The routing/reliability core is specified once in [SPEC.md](SPEC.md) and
implemented in both Kotlin (here) and Python. Behavioral parity of the
deterministic core is **enforced** by shared conformance vectors:
[`conformance/vectors.json`](conformance/vectors.json) is byte-identical to the
Python repo's copy, and both test suites must pass it.

```bash
./gradlew :hybridinfer:test        # runs ConformanceTest against the shared vectors
```

## Scope / honesty

- **Library, not an inference engine.** You provide the on-device engine (MLC-LLM
  integration is app-specific). `OpenAiEngine` is a ready-to-use remote engine.
- The pure-Kotlin core and `OpenAiEngine` are dependency-light (only gson) and
  JVM-unit-tested; the Android-specific `ThermalSignal` compiles under the Android
  SDK.
- Published via JitPack (`v0.1.0`); not on Maven Central.

## License

Apache-2.0. See [LICENSE](LICENSE).
