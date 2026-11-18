package com.nfcguard.app

import kotlin.math.sqrt

data class RotationSample(val tNanos: Long, val gx: Float, val gy: Float, val gz: Float)

enum class TapVerdict { NORMAL, ABNORMAL, UNKNOWN }

object TapDetectionRule {
    private const val WINDOW_NANOS = 2_000_000_000L
    private const val MAX_GAP_NANOS = 250_000_000L
    private const val MIN_ADJUSTMENT_RADIANS = 0.26 // 약 15도, 실험용 초기값

    fun evaluate(samples: List<RotationSample>, detectedAtNanos: Long, movement: MovementState): TapVerdict {
        val rotation = angularTravel(samples, detectedAtNanos) ?: return TapVerdict.UNKNOWN
        val adjusted = rotation >= MIN_ADJUSTMENT_RADIANS
        return when {
            !adjusted && movement == MovementState.MOVING -> TapVerdict.ABNORMAL
            adjusted && movement == MovementState.STATIONARY -> TapVerdict.NORMAL
            else -> TapVerdict.UNKNOWN
        }
    }

    private fun angularTravel(samples: List<RotationSample>, detectedAtNanos: Long): Double? {
        val start = detectedAtNanos - WINDOW_NANOS
        var previousTime: Long? = null
        var previousSpeed = 0.0
        var radians = 0.0
        for (sample in samples) {
            if (sample.tNanos < start || sample.tNanos > detectedAtNanos) continue
            val x = sample.gx.toDouble()
            val y = sample.gy.toDouble()
            val z = sample.gz.toDouble()
            val speed = sqrt(x * x + y * y + z * z)
            if (!speed.isFinite()) return null
            val prior = previousTime
            if (prior == null) {
                if (sample.tNanos - start > MAX_GAP_NANOS) return null
            } else {
                val gap = sample.tNanos - prior
                if (gap <= 0 || gap > MAX_GAP_NANOS) return null
                radians += (speed + previousSpeed) * 0.5 * (gap / 1_000_000_000.0)
            }
            previousTime = sample.tNanos
            previousSpeed = speed
        }
        val last = previousTime ?: return null
        return radians.takeIf { detectedAtNanos - last <= MAX_GAP_NANOS }
    }
}
