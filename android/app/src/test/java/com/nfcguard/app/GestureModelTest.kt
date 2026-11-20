package com.nfcguard.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ml-python 산출물과 앱 쪽 처리가 정확히 맞물리는지 검증 (픽스처는 ml-python 코드로 생성한 값) */
class GestureModelTest {

    private val features = listOf("ax", "ay", "az", "gx", "gy", "gz")

    @Test
    fun resampleMatchesPythonDataLoader() {
        // data_loader._resample_window(win, 4) 결과, win[k] = {ax:k, ay:k^2, az:-1, gx:0.5k, gy:0, gz:2}
        val samples = (0 until 5).map { k ->
            MotionSample(k * 20_000_000L, k.toFloat(), (k * k).toFloat(), -1f, 0.5f * k, 0f, 2f)
        }
        val expected = arrayOf(
            floatArrayOf(0f, 0f, -1f, 0f, 0f, 2f),
            floatArrayOf(1.333333f, 2.0f, -1f, 0.666667f, 0f, 2f),
            floatArrayOf(2.666667f, 7.333334f, -1f, 1.333333f, 0f, 2f),
            floatArrayOf(4f, 16f, -1f, 2f, 0f, 2f)
        )
        val actual = SensorPreprocessor.resample(samples, 4, features)
        for (i in expected.indices) assertArrayEquals(expected[i], actual[i], 1e-5f)
    }

    @Test
    fun resampleWithTooFewSamplesIsZeros() {
        val out = SensorPreprocessor.resample(emptyList(), 3, features)
        assertTrue(out.all { row -> row.all { it == 0f } })
    }

    @Test
    fun verifiesSignatureFromModelExport() {
        // model_export.sign(b'{"version": 7, "sha256": "ab"}', 테스트 전용 키)
        val meta = """{"version": 7, "sha256": "ab"}""".toByteArray()
        assertTrue(ModelSignatureVerifier.verify(meta, TEST_SIGNATURE, TEST_PUBLIC_KEY))
        assertFalse(ModelSignatureVerifier.verify("""{"version": 8, "sha256": "ab"}""".toByteArray(), TEST_SIGNATURE, TEST_PUBLIC_KEY))
        assertFalse(ModelSignatureVerifier.verify(meta, null, TEST_PUBLIC_KEY))
        assertFalse(ModelSignatureVerifier.verify(meta, "not-base64!", TEST_PUBLIC_KEY))
    }

    @Test
    fun modelRiskMapsThresholdToHalf() {
        assertEquals(0.5, RiskBlender.modelRisk(0.7, 0.7), 1e-9)
        assertEquals(1.0, RiskBlender.modelRisk(0.0, 0.7), 1e-9)
        assertEquals(0.0, RiskBlender.modelRisk(1.0, 0.7), 1e-9)
        assertTrue(RiskBlender.modelRisk(0.6, 0.7) > 0.5)
    }

    @Test
    fun blendRecomputesReasonAndRecordsModelVersion() {
        val heuristic = GestureMatcher.Result(0.3, 0.0, 0.0, "gesture_matches_expected_tap_pattern", 1.0)
        val spec = GestureModelSpec(42, "x", 96, features, "linear_by_index", 0.7, 0.5)
        val blended = RiskBlender.blend(heuristic, pIntentional = 0.0, spec = spec)
        assertEquals(0.65, blended.riskScore, 1e-9)
        assertEquals("ambiguous_low_confidence", blended.reason)
        assertEquals(42L, blended.modelVersion)
    }

    @Test
    fun rejectsUnsupportedSpec() {
        assertTrue(GestureModelSpec(1, "x", 96, features, "linear_by_index", 0.5, 0.5).isSupported())
        assertFalse(GestureModelSpec(1, "x", 96, features, "linear_by_time", 0.5, 0.5).isSupported())
        assertFalse(GestureModelSpec(1, "x", 96, listOf("mx"), "linear_by_index", 0.5, 0.5).isSupported())
    }

    companion object {
        private const val TEST_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwNYEiymCUK5nv8ndvuOkAoVcwXpYv/UUWKXl/zwtj9gY/5pqx/xJC1Affl9BD+iCEEOyf0zM5J4w9UyvBQ5nfw=="
        private const val TEST_SIGNATURE =
            "MEYCIQCRZX7rqMN3j9dwaPumEAyJ7kbv9eCVnRVekbQ8vQi6xQIhAOW+GUeXUQF7bo6+wvAEzqO+if7GnuS/SJhxEWecFCZl"
    }
}
