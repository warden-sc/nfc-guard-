package com.nfcguard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TapDetectionRuleTest {
    private val eventAt = 9_000_000_000L

    private fun samples(speed: Float) = (0..20).map {
        RotationSample(eventAt - 2_000_000_000L + it * 100_000_000L, speed, 0f, 0f)
    }

    @Test fun noRotationWhileMovingIsAbnormal() {
        assertEquals(TapVerdict.ABNORMAL, TapDetectionRule.evaluate(samples(0f), eventAt, MovementState.MOVING))
    }

    @Test fun rotationWhileStationaryIsNormal() {
        assertEquals(TapVerdict.NORMAL, TapDetectionRule.evaluate(samples(0.5f), eventAt, MovementState.STATIONARY))
    }

    @Test fun unsupportedCombinationsStayUndetermined() {
        assertEquals(TapVerdict.UNKNOWN, TapDetectionRule.evaluate(samples(0.5f), eventAt, MovementState.MOVING))
        assertEquals(TapVerdict.UNKNOWN, TapDetectionRule.evaluate(samples(0f), eventAt, MovementState.STATIONARY))
        assertEquals(TapVerdict.UNKNOWN, TapDetectionRule.evaluate(samples(0.5f), eventAt, MovementState.UNKNOWN))
    }

    @Test fun sensorGapsAreNotEvidenceOfNoRotation() {
        val gap = samples(0f).filterIndexed { index, _ -> index !in 7..12 }
        assertEquals(TapVerdict.UNKNOWN, TapDetectionRule.evaluate(gap, eventAt, MovementState.MOVING))
        assertEquals(TapVerdict.UNKNOWN, TapDetectionRule.evaluate(emptyList(), eventAt, MovementState.MOVING))
        assertEquals(TapVerdict.UNKNOWN, TapDetectionRule.evaluate(samples(0f).dropLast(5), eventAt, MovementState.MOVING))
    }

    @Test fun rotationAfterTheEventIsIgnored() {
        val later = RotationSample(eventAt + 100_000_000L, 90f, 0f, 0f)
        assertEquals(TapVerdict.ABNORMAL, TapDetectionRule.evaluate(samples(0f) + later, eventAt, MovementState.MOVING))
    }

    @Test fun locationEvidenceCanDriveTheRuleAndExpires() {
        val locationRule = LocationMovementRule()
        for (time in listOf(7000L, 8000L, 9000L)) {
            locationRule.update(MovementFix(time, 1.5, 0.1, 5.0), time)
        }
        assertEquals(TapVerdict.ABNORMAL, TapDetectionRule.evaluate(samples(0f), eventAt, locationRule.snapshot(9000).state))
        assertEquals(TapVerdict.UNKNOWN, TapDetectionRule.evaluate(samples(0f), eventAt, locationRule.snapshot(15000).state))
    }
}
