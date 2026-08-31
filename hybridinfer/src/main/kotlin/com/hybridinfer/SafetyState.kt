package com.hybridinfer

/** Safety state machine with hysteresis and probe-based recovery (SPEC.md section 4). */
enum class SafetyState { LOCAL_ELIGIBLE, CAUTION, UNSAFE, RECOVERING, RESTORED }

class SafetyStateMachine(
    private val cautionPfail: Double = 0.5,
    private val unsafeFailures: Int = 2,
    private val recoveryCooldownS: Double = 60.0,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
) {
    var state: SafetyState = SafetyState.LOCAL_ELIGIBLE
        private set

    private var consecFail = 0
    private var unsafeSince: Double? = null

    /** Soft, pre-request gating: elevated predicted risk -> CAUTION. */
    fun onDecision(pfail: Double) {
        if ((state == SafetyState.LOCAL_ELIGIBLE || state == SafetyState.RESTORED) &&
            pfail >= cautionPfail
        ) {
            state = SafetyState.CAUTION
        }
    }

    /** Whether a local attempt is permitted right now (allows one probe post-cooldown). */
    fun localAllowed(): Boolean {
        if (state == SafetyState.LOCAL_ELIGIBLE ||
            state == SafetyState.CAUTION ||
            state == SafetyState.RESTORED
        ) {
            return true
        }
        val since = unsafeSince
        if (since != null && (clock() - since) >= recoveryCooldownS) {
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
            }
            SafetyState.CAUTION, SafetyState.RESTORED -> state = SafetyState.LOCAL_ELIGIBLE
            else -> {}
        }
    }

    fun onLocalFailure() {
        consecFail += 1
        state = when {
            state == SafetyState.RECOVERING -> {   // probe failed
                unsafeSince = clock()
                SafetyState.UNSAFE
            }
            consecFail >= unsafeFailures -> {
                unsafeSince = clock()
                SafetyState.UNSAFE
            }
            else -> SafetyState.CAUTION
        }
    }
}
