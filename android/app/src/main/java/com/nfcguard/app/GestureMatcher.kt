package com.nfcguard.app

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 판정 로직 요약 (설계 논의 그대로 구현):
 *
 * 1) 보행 주기 성분(약 1.5~2.5Hz)을 밴드리젝트로 제거 -> 잔차만 남김
 * 2) 잔차를 짧은 창(<=1.5s)에서만 이중적분 -> 순변위 추정 (드리프트 누적 방지)
 * 3) 자이로로 "평평해지는 자세 변화(태깅 자세)"가 있었는지 체크
 * 4) 위 신호들을 합쳐 0.0(정상) ~ 1.0(의심) 위험도 스코어 반환
 *
 * 단일 이벤트로 확정하지 않고, 호출부(AnomalyReporter)에서 반복 빈도까지 누적 판단한다.
 */
object GestureMatcher {

    data class Result(
        val riskScore: Double,       // 0.0 ~ 1.0
        val netDisplacement: Double, // m, 참고용
        val orientationShift: Double,// rad, 참고용
        val reason: String
    )

    // 사람 보행 주기 대역 (Hz)
    private const val GAIT_LOW_HZ = 1.3
    private const val GAIT_HIGH_HZ = 2.6

    fun evaluate(samples: List<MotionSample>, baseline: UserGestureBaseline?): Result {
        if (samples.size < 8) {
            // 데이터 부족 -> 판단 보류, 낮은 위험도로 취급 (오탐 방지 우선)
            return Result(0.1, 0.0, 0.0, "insufficient_samples")
        }

        val dtSeconds = estimateAvgDt(samples)
        if (dtSeconds <= 0.0) return Result(0.1, 0.0, 0.0, "invalid_dt")

        // 1) 보행 주기 성분 제거 (단순 이동평균 기반 band-reject: 저역통과 - 그 결과를 원신호에서 빼면 고역 잔차)
        val gaitFiltered = bandRejectGait(samples.map { it.ax }, dtSeconds)
        val gaitFilteredY = bandRejectGait(samples.map { it.ay }, dtSeconds)
        val gaitFilteredZ = bandRejectGait(samples.map { it.az }, dtSeconds)

        // 2) 짧은 창(최근 1.5초)에서만 이중적분 -> 순변위 추정
        val shortWindow = min(samples.size, (1.5 / dtSeconds).toInt().coerceAtLeast(4))
        val netDisp = estimateNetDisplacement(
            gaitFiltered.takeLast(shortWindow),
            gaitFilteredY.takeLast(shortWindow),
            gaitFilteredZ.takeLast(shortWindow),
            dtSeconds
        )

        // 3) 자세 변화량 (자이로 각속도 적분 근사)
        val orientationShift = samples.takeLast(shortWindow).sumOf {
            sqrt((it.gx * it.gx + it.gy * it.gy + it.gz * it.gz).toDouble()) * dtSeconds
        }

        // 4) 개인화 baseline과 비교 (없으면 범용 임계값 사용)
        val dispThreshold = baseline?.typicalNetDisplacement ?: DEFAULT_DISP_THRESHOLD
        val orientThreshold = baseline?.typicalOrientationShift ?: DEFAULT_ORIENT_THRESHOLD

        val dispScore = (1.0 - (netDisp / dispThreshold)).coerceIn(0.0, 1.0)
        val orientScore = (1.0 - (orientationShift / orientThreshold)).coerceIn(0.0, 1.0)

        // 변위도 없고 자세변화도 없으면 -> "의도적 태깅 동작 없음" -> 위험도 상승
        val riskScore = (0.6 * dispScore + 0.4 * orientScore).coerceIn(0.0, 1.0)

        val reason = when {
            riskScore > 0.7 -> "no_intentional_gesture_detected"
            riskScore > 0.4 -> "ambiguous_low_confidence"
            else -> "gesture_matches_expected_tap_pattern"
        }

        return Result(riskScore, netDisp, orientationShift, reason)
    }

    private fun estimateAvgDt(samples: List<MotionSample>): Double {
        if (samples.size < 2) return 0.0
        val totalNanos = samples.last().tNanos - samples.first().tNanos
        return (totalNanos.toDouble() / 1_000_000_000.0) / (samples.size - 1)
    }

    /** 이동평균으로 보행 주기 저역 성분을 뽑고, 원신호 - 저역성분 = 고역 잔차 */
    private fun bandRejectGait(values: List<Float>, dtSeconds: Double): List<Double> {
        // 보행 주기(약 1.3~2.6Hz)에 대응하는 이동평균 윈도 크기
        val avgGaitPeriodSec = 1.0 / ((GAIT_LOW_HZ + GAIT_HIGH_HZ) / 2.0)
        val windowSize = (avgGaitPeriodSec / dtSeconds).toInt().coerceIn(2, values.size)

        val lowPass = DoubleArray(values.size)
        for (i in values.indices) {
            val start = (i - windowSize / 2).coerceAtLeast(0)
            val end = (i + windowSize / 2).coerceAtMost(values.size - 1)
            var sum = 0.0
            for (j in start..end) sum += values[j]
            lowPass[i] = sum / (end - start + 1)
        }
        return values.indices.map { values[it] - lowPass[it] }
    }

    private fun estimateNetDisplacement(
        rx: List<Double>, ry: List<Double>, rz: List<Double>, dtSeconds: Double
    ): Double {
        // 잔차 가속도를 짧은 창에서 두 번 적분 (드리프트가 누적되기 전 구간만 사용하므로 근사로 충분)
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        var px = 0.0; var py = 0.0; var pz = 0.0
        for (i in rx.indices) {
            vx += rx[i] * dtSeconds; vy += ry[i] * dtSeconds; vz += rz[i] * dtSeconds
            px += vx * dtSeconds; py += vy * dtSeconds; pz += vz * dtSeconds
        }
        return sqrt(px * px + py * py + pz * pz)
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    private const val DEFAULT_DISP_THRESHOLD = 0.02   // m, 범용 기본값 (온보딩 전)
    private const val DEFAULT_ORIENT_THRESHOLD = 0.5  // rad, 범용 기본값
}

/** 온보딩 시 학습한 사용자 고유 태깅 제스처 프로필 */
data class UserGestureBaseline(
    val typicalNetDisplacement: Double,
    val typicalOrientationShift: Double,
    val sampleCount: Int
)
