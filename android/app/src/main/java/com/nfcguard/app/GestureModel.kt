package com.nfcguard.app

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/*
 * 온디바이스 AI 모델의 순수 로직 (안드로이드 API 의존 없음 -> JVM 단위 테스트 가능).
 * 실제 TFLite 실행/다운로드는 TfliteGestureClassifier.kt, ModelUpdater.kt.
 */

/** ml-python/model_export.py build_metadata()의 앱 측 표현 */
data class GestureModelSpec(
    val version: Long,
    val sha256: String,
    val windowLen: Int,
    val features: List<String>,
    val resample: String,
    val threshold: Double,   // p(의도적) < threshold 이면 의심
    val blendWeight: Double  // 휴리스틱 대비 모델 점수 비중 (0~1)
) {
    companion object {
        const val SUPPORTED_RESAMPLE = "linear_by_index"
        val SUPPORTED_FEATURES = setOf("ax", "ay", "az", "gx", "gy", "gz")
    }

    fun isSupported(): Boolean =
        resample == SUPPORTED_RESAMPLE && windowLen in 8..1024 &&
            features.isNotEmpty() && features.all { it in SUPPORTED_FEATURES } &&
            threshold in 0.0..1.0 && blendWeight in 0.0..1.0
}

object SensorPreprocessor {

    /**
     * ml-python/data_loader.py _resample_window()와 동일: 인덱스 기준 선형보간으로 windowLen 길이로 맞춤.
     * 반환은 [windowLen][features.size]를 행 우선으로 펼친 배열. 샘플이 2개 미만이면 0으로 채움.
     */
    fun resample(samples: List<MotionSample>, windowLen: Int, features: List<String>): Array<FloatArray> {
        val out = Array(windowLen) { FloatArray(features.size) }
        if (samples.size < 2) return out
        val n = samples.size
        for (i in 0 until windowLen) {
            val pos = if (windowLen == 1) 0.0 else i.toDouble() * (n - 1) / (windowLen - 1)
            val lo = pos.toInt().coerceAtMost(n - 1)
            val hi = (lo + 1).coerceAtMost(n - 1)
            val frac = pos - lo
            for ((c, f) in features.withIndex()) {
                val a = channel(samples[lo], f)
                val b = channel(samples[hi], f)
                out[i][c] = (a + (b - a) * frac).toFloat()
            }
        }
        return out
    }

    private fun channel(s: MotionSample, name: String): Double = when (name) {
        "ax" -> s.ax.toDouble(); "ay" -> s.ay.toDouble(); "az" -> s.az.toDouble()
        "gx" -> s.gx.toDouble(); "gy" -> s.gy.toDouble(); "gz" -> s.gz.toDouble()
        else -> 0.0
    }
}

object RiskBlender {

    /**
     * 모델 출력 p(의도적 태그)를 위험도로 변환: 검증셋에서 고른 threshold가 위험도 0.5에 오도록 구간 선형 매핑.
     * (단순 1-p는 클래스 불균형 때문에 threshold가 0.5가 아닐 때 휴리스틱 점수와 스케일이 안 맞음)
     */
    fun modelRisk(pIntentional: Double, threshold: Double): Double {
        val p = pIntentional.coerceIn(0.0, 1.0)
        val t = threshold.coerceIn(1e-6, 1.0 - 1e-6)
        return if (p < t) 0.5 + 0.5 * (t - p) / t else 0.5 * (1.0 - p) / (1.0 - t)
    }

    /** 휴리스틱(GestureMatcher) 결과에 모델 점수를 섞는다. 가방/주머니 신호는 휴리스틱 쪽에 이미 반영됨 */
    fun blend(heuristic: GestureMatcher.Result, pIntentional: Double, spec: GestureModelSpec): GestureMatcher.Result {
        val risk = ((1.0 - spec.blendWeight) * heuristic.riskScore +
            spec.blendWeight * modelRisk(pIntentional, spec.threshold)).coerceIn(0.0, 1.0)
        return heuristic.copy(
            riskScore = risk,
            reason = GestureMatcher.reasonFor(risk, heuristic.pocketScore),
            modelVersion = spec.version
        )
    }
}

object ModelSignatureVerifier {

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * model_export.py sign()과 짝: 메타데이터 원문 바이트에 대한 SHA256withECDSA(P-256, DER) 서명 검증.
     * publicKeyB64 = X.509 SubjectPublicKeyInfo DER의 base64 (model_signing_public.b64)
     */
    fun verify(metadataBytes: ByteArray, signatureB64: String?, publicKeyB64: String): Boolean {
        if (signatureB64.isNullOrBlank()) return false
        return try {
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64.trim())))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(metadataBytes)
                verify(Base64.getDecoder().decode(signatureB64.trim()))
            }
        } catch (e: Exception) {
            false
        }
    }
}
