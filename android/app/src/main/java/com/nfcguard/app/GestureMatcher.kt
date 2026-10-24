package com.nfcguard.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 판정 로직 요약 (설계 논의 그대로 구현):
 *
 * 1) 보행 주기 성분(약 1.5~2.5Hz)을 밴드리젝트로 제거 -> 잔차만 남김
 * 2) 잔차를 짧은 창(<=1.5s)에서만 이중적분 -> 순변위 추정 (드리프트 누적 방지)
 * 3) 자이로로 "평평해지는 자세 변화(태깅 자세)"가 있었는지 체크
 * 4) 근접센서/조도/화면상태로 "가방·주머니 안에 있었는지" 체크 (DeviceContext)
 * 5) 위 신호들을 합쳐 0.0(정상) ~ 1.0(의심) 위험도 스코어 반환
 *
 * 단일 이벤트로 확정하지 않고, 호출부(AnomalyReporter)에서 반복 빈도까지 누적 판단한다.
 */
object GestureMatcher {

    data class Result(
        val riskScore: Double,       // 0.0 ~ 1.0
        val netDisplacement: Double, // m, 참고용
        val orientationShift: Double,// rad, 참고용
        val reason: String,
        val pocketScore: Double = 0.0 // 0.0 ~ 1.0, 가방/주머니 안일 가능성
    )

    // 사람 보행 주기 대역 (Hz)
    private const val GAIT_LOW_HZ = 1.3
    private const val GAIT_HIGH_HZ = 2.6

    fun evaluate(
        samples: List<MotionSample>,
        baseline: UserGestureBaseline?,
        deviceContext: DeviceContext? = null
    ): Result {
        if (samples.size < 8) {
            // 데이터 부족 -> 판단 보류, 낮은 위험도로 취급 (오탐 방지 우선)
            return Result(0.1, 0.0, 0.0, "insufficient_samples")
        }

        val dtSeconds = estimateAvgDt(samples)
        if (dtSeconds <= 0.0) return Result(0.1, 0.0, 0.0, "invalid_dt")

        // 1) 보행 주기 성분 제거 (biquad 노치 필터, 순방향+역방향 적용으로 위상지연 없음)
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
        val motionRisk = (0.6 * dispScore + 0.4 * orientScore).coerceIn(0.0, 1.0)

        // 5) 가방/주머니 신호를 noisy-OR로 합산: 동작 신호가 애매해도 "가려지고 어두우면" 위험도 상승.
        //    단, 꺼내는 동작이 뚜렷하면(motionRisk 낮음) 손가락이 근접센서를 가린 경우일 수 있어 비중을 절반까지 낮춤.
        val pocketScore = estimatePocketScore(deviceContext)
        val pocketContribution = POCKET_WEIGHT * pocketScore * (0.5 + 0.5 * motionRisk)
        val screenOffBoost = if (deviceContext?.screenInteractive == false) SCREEN_OFF_WEIGHT else 0.0
        val riskScore = (1.0 - (1.0 - motionRisk) * (1.0 - pocketContribution) * (1.0 - screenOffBoost))
            .coerceIn(0.0, 1.0)

        val reason = when {
            riskScore > 0.7 && pocketScore >= 0.6 -> "tapped_while_in_bag_or_pocket"
            riskScore > 0.7 -> "no_intentional_gesture_detected"
            riskScore > 0.4 -> "ambiguous_low_confidence"
            else -> "gesture_matches_expected_tap_pattern"
        }

        return Result(riskScore, netDisp, orientationShift, reason, pocketScore)
    }

    /**
     * 근접센서가 가려져 있고 어두우면 가방/주머니 안일 가능성이 높다.
     * 사용자가 직접 태그할 때는 보통 폰 뒷면을 리더기에 대므로 앞면 근접센서는 열려 있다.
     * 센서가 없는 기종은 0으로 취급 (오탐 방지 우선).
     */
    private fun estimatePocketScore(ctx: DeviceContext?): Double {
        if (ctx == null) return 0.0
        val near = ctx.proximityNear == true
        val dark = ctx.ambientLux?.let { it <= DARK_LUX } == true
        return when {
            near && dark -> 1.0
            near -> 0.6
            dark -> 0.3
            else -> 0.0
        }
    }

    private fun estimateAvgDt(samples: List<MotionSample>): Double {
        if (samples.size < 2) return 0.0
        val totalNanos = samples.last().tNanos - samples.first().tNanos
        return (totalNanos.toDouble() / 1_000_000_000.0) / (samples.size - 1)
    }

    /**
     * 보행 주기 대역(GAIT_LOW_HZ~GAIT_HIGH_HZ)만 깎아내는 2차 노치(band-stop) 필터.
     * 순방향 + 역방향 두 번 적용(filtfilt)해서 위상지연을 없앤다 - 2.5초 버퍼 전체가 이미 있으므로 가능.
     *
     * (이전 구현의 "원신호 - 한 주기 이동평균"은 보행 주파수를 오히려 그대로 남기는 버그가 있었음:
     *  한 주기 길이 이동평균은 그 주파수 성분을 0으로 만드므로, 원신호에서 빼면 보행 성분이 고스란히 잔차에 남는다.)
     */
    private fun bandRejectGait(values: List<Float>, dtSeconds: Double): List<Double> {
        val x = DoubleArray(values.size) { values[it].toDouble() }
        val f0 = sqrt(GAIT_LOW_HZ * GAIT_HIGH_HZ)      // 대역 중심 (기하평균)
        val q = f0 / (GAIT_HIGH_HZ - GAIT_LOW_HZ)       // 대역폭 -> Q
        if (f0 * dtSeconds >= 0.5) return x.toList()    // 샘플링이 너무 느리면(나이퀴스트 초과) 필터링 생략

        val forward = notch(x, f0, q, dtSeconds)
        val backward = notch(forward.reversedArray(), f0, q, dtSeconds)
        return backward.reversedArray().toList()
    }

    /** RBJ Audio EQ Cookbook 노치 biquad. 초기 상태를 첫 샘플의 정상상태로 맞춰 과도응답을 줄인다 (DC 이득 = 1). */
    private fun notch(x: DoubleArray, f0: Double, q: Double, dtSeconds: Double): DoubleArray {
        if (x.isEmpty()) return x
        val w0 = 2.0 * PI * f0 * dtSeconds
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = 1.0 / a0
        val b1 = -2.0 * cosW0 / a0
        val b2 = 1.0 / a0
        val a1 = -2.0 * cosW0 / a0
        val a2 = (1.0 - alpha) / a0

        val y = DoubleArray(x.size)
        var x1 = x[0]; var x2 = x[0]; var y1 = x[0]; var y2 = x[0]
        for (i in x.indices) {
            val out = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]
            y2 = y1; y1 = out
            y[i] = out
        }
        return y
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

    // 가방/주머니 판정 (실사용 데이터로 튜닝 필요)
    private const val DARK_LUX = 10f          // 이 이하면 "어두움" (가방 안/주머니 안 근사)
    private const val POCKET_WEIGHT = 0.7     // 가방/주머니 신호가 최대일 때 위험도 기여
    private const val SCREEN_OFF_WEIGHT = 0.15 // 화면 꺼짐은 약한 신호 (교통카드는 화면 꺼진 채 정상 사용이 흔함)
}

/** 온보딩 시 학습한 사용자 고유 태깅 제스처 프로필 */
data class UserGestureBaseline(
    val typicalNetDisplacement: Double,
    val typicalOrientationShift: Double,
    val sampleCount: Int
)
