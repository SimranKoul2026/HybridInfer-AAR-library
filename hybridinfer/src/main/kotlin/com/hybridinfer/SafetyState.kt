package com.hybridinfer

/** Safety state machine with hysteresis and probe-based recovery (SPEC.md section 4).
 *  Recovery probe cooldown grows exponentially on repeated failures, capped. */
enum class SafetyState { LOCAL_ELIGIBLE, CAUTION, UNSAFE, RECOVERING, RESTORED }

class SafetyStateMachine(
    private val cautionPfail: Double = 0.5,
    private val unsafeFailures: Int = 2,
    private val recoveryCooldownS: Double = 60.0,
    private val recoveryBackoff: Double = 2.0,
    private val recoveryCooldownMaxS: Double = 600.0,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
) {
    var state: SafetyState = SafetyState.LOCAL_ELIGIBLE
        private set

    private var consecFail = 0
    private var unsafeSince: Double? = null
    private var probeFailures = 0

    private fun cooldown(): Double =
        minOf(recoveryCooldownS * Math.pow(recoveryBackoff, probeFailures.toDouble()), recoveryCooldownMaxS)

    /** Soft, pre-request gating: elevated predicted risk -> CAUTION. */
    fun onDecision(pfail: Double) {
        if ((state == SafetyState.LOCAL_ELIGIBLE || state == SafetyState.RESTORED) &&
            pfail >= cautionPfail
        ) {
            state = SafetyState.CAUTION
        }
    }

    /** Whether a local attempt is permitted right now (one probe once the backed-off cooldown elapses). */
    fun localAllowed(): Boolean {
        if (state == SafetyState.LOCAL_ELIGIBLE ||
            state == SafetyState.CAUTION ||
            state == SafetyState.RESTORED
        ) {
            return true
        }
        val since = unsafeSince
        if (since != null && (clock() - since) >= cooldown()) {
            state = SafetyState.RECOVERING
            return true
        }
        return false
    }

    fun onLocalSuccess() {
        consecFail = 0
        when (state) {
            SafetyState.RECOVERING -> {
                state = SafetyState.RESTORED
                unsafeSince = null
                probeFailures = 0            // recovered: reset the backoff
            }
            SafetyState.CAUTION, SafetyState.RESTORED -> state = SafetyState.LOCAL_ELIGIBLE
            else -> {}
        }
    }

    fun onLocalFailure() {
        consecFail += 1
        state = when {
            state == SafetyState.RECOVERING -> {   // probe failed -> grow cooldown
                probeFailures += 1
                unsafeSince = clock()
                SafetyState.UNSAFE
            }
            consecFail >= unsafeFailures -> {       // new UNSAFE episode -> base cooldown
                probeFailures = 0
                unsafeSince = clock()
                SafetyState.UNSAFE
            }
            else -> SafetyState.CAUTION
        }
    }
}
