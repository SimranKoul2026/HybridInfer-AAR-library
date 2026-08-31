package com.hybridinfer.android

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Optional Android-only thermal signal - the input the desktop tool cannot have.
 *
 * On Android S/R+ (API 30+), `PowerManager.getThermalHeadroom()` returns
 * ~0.0 (cool) .. 1.0 (throttling imminent). Use it to bias routing - e.g. prefer
 * the remote tier when the device is hot - by selecting a [com.hybridinfer.ControllerConfig]
 * with `forceRemote = true`, or by gating your local [com.hybridinfer.Engine].
 *
 * This is intentionally NOT wired into the cross-platform routing core, so the
 * Kotlin and Python implementations stay in conformance (SPEC.md section 9). It
 * is a host-side helper you compose on top.
 */
object ThermalSignal {

    /** Thermal headroom in [0,1] (higher = hotter), or null if unavailable. */
    fun headroom(context: Context, forecastSeconds: Int = 10): Float? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val h = pm.getThermalHeadroom(forecastSeconds)
            if (h.isNaN()) null else h
        } catch (e: Exception) {
            null
        }
    }

    /** Convenience: is the device hot enough that local inference should be avoided? */
    fun shouldAvoidLocal(context: Context, threshold: Float = 0.85f): Boolean {
        val h = headroom(context) ?: return false
        return h >= threshold
    }
}
