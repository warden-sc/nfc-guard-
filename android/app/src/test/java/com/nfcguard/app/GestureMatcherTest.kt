package com.nfcguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** 50Hz x 2.5초 합성 신호로 "의도한 태그 vs 의도하지 않은 태그" 판정을 검증 */
class GestureMatcherTest {

    private fun samples(fn: (Double) -> DoubleArray): List<MotionSample> =
        (0 until 125).map { i ->
            val t = i * 0.02
            val v = fn(t)
            MotionSample(
                (t * 1e9).toLong(),
                v[0].toFloat(), v[1].toFloat(), v[2].toFloat(),
                v[3].toFloat(), v[4].toFloat(), v[5].toFloat()
            )
        }

    // 걷는 중 가방 안: 보행 진동(1.9Hz)만 있고 꺼내는 동작 없음
    private val walkingInBag = samples { t ->
        val w = 2 * PI * 1.9 * t
        doubleArrayOf(1.5 * sin(w), 0.8 * sin(w + 1), 2.0 * sin(w), 0.05, 0.05, 0.05)
    }

    // 폰을 꺼내 리더기에 댐: 태그 직전 큰 저주파 이동 + 회전
    private val pullOut = samples { t ->
        val p = exp(-((t - 1.9) / 0.18).pow(2))
        doubleArrayOf(4 * p * (t - 1.9) / 0.18, 3 * p, 2 * p, 2.5 * p, 1.5 * p, 0.5 * p)
    }

    private val still = samples { DoubleArray(6) }
    private val inBag = DeviceContext(proximityNear = true, ambientLux = 1f, screenInteractive = false)
    private val inHand = DeviceContext(proximityNear = false, ambientLux = 300f, screenInteractive = true)

    @Test
    fun stillDeviceInBagIsFlaggedAsBagTap() {
        val r = GestureMatcher.evaluate(still, null, inBag)
        assertTrue(r.riskScore > 0.7)
        assertEquals("tapped_while_in_bag_or_pocket", r.reason)
    }

    @Test
    fun walkingGaitAloneIsNotMistakenForTapGesture() {
        // 보행 성분이 제대로 제거되지 않으면 걷기 진동이 "꺼내는 동작"으로 오인되어 위험도가 낮아짐
        assertTrue(GestureMatcher.evaluate(walkingInBag, null, null).riskScore > 0.7)
        assertTrue(GestureMatcher.evaluate(walkingInBag, null, inBag).riskScore > 0.9)
    }

    @Test
    fun intentionalPullOutIsLowRisk() {
        assertTrue(GestureMatcher.evaluate(pullOut, null, inHand).riskScore < 0.4)
    }

    @Test
    fun clearGestureWithCoveredSensorIsOnlyAmbiguous() {
        // 손가락이 근접센서를 가린 채 직접 태그한 경우 -> 즉시 알림(0.7) 대상이 아님
        val r = GestureMatcher.evaluate(pullOut, null, inBag)
        assertTrue(r.riskScore in 0.4..0.7)
    }

    @Test
    fun missingContextAddsNoPocketSignal() {
        assertEquals(0.0, GestureMatcher.evaluate(still, null, null).pocketScore, 0.0)
    }
}
