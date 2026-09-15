package com.nfcguard.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * "평소처럼 카드/폰을 리더기에 태그하는 동작을 5~10회 반복해주세요" 온보딩 화면.
 * 매 반복마다 GestureMatcher의 순변위/자세변화 원시값을 모아 평균 -> 개인 baseline 생성.
 *
 * 실제 UI(XML 레이아웃)는 생략하고 로직 흐름만 스캐폴딩으로 제공.
 */
class OnboardingActivity : Activity() {

    private val collectedDisplacements = mutableListOf<Double>()
    private val collectedOrientations = mutableListOf<Double>()
    private lateinit var motionBuffer: MotionBuffer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        motionBuffer = (application as NfcGuardApp).motionBuffer
        // setContentView(R.layout.activity_onboarding) // 레이아웃은 프로젝트에서 구성
    }

    /** "지금 태그했어요" 버튼 클릭 시 호출 -> 그 순간 버퍼를 샘플로 채택 */
    fun onUserConfirmedTapGesture() {
        val samples = motionBuffer.snapshot()
        val result = GestureMatcher.evaluate(samples, baseline = null)
        collectedDisplacements.add(result.netDisplacement)
        collectedOrientations.add(result.orientationShift)

        if (collectedDisplacements.size >= MIN_SAMPLES) {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        val avgDisp = collectedDisplacements.average()
        val avgOrient = collectedOrientations.average()
        // 개인 baseline은 평균의 절반 정도를 임계값으로 (여유 마진) - 실사용 데이터로 튜닝 필요
        val baseline = UserGestureBaseline(
            typicalNetDisplacement = avgDisp * 0.5,
            typicalOrientationShift = avgOrient * 0.5,
            sampleCount = collectedDisplacements.size
        )
        GestureBaselineStore.save(this, baseline)
        Toast.makeText(this, "온보딩 완료: 개인 태깅 패턴 학습됨", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val MIN_SAMPLES = 6
    }
}
