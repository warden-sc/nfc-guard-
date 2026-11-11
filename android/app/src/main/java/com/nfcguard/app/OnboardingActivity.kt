package com.nfcguard.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nfcguard.app.databinding.ActivityOnboardingBinding

/**
 * "평소처럼 카드/폰을 리더기에 태그하는 동작을 반복해주세요" 온보딩 화면.
 * 매 반복마다 GestureMatcher의 순변위/자세변화 원시값을 모아 평균 -> 개인 baseline 생성.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val collectedDisplacements = mutableListOf<Double>()
    private val collectedOrientations = mutableListOf<Double>()
    private lateinit var motionBuffer: MotionBuffer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        motionBuffer = (application as NfcGuardApp).motionBuffer
        motionBuffer.start() // 보호가 꺼져 있어도 등록 중에는 센서가 필요

        binding.progressBar.max = MIN_SAMPLES
        binding.closeButton.setOnClickListener { finish() }
        binding.tapButton.setOnClickListener { onUserConfirmedTapGesture() }
        renderProgress()
    }

    /** "방금 태그했어요" 버튼 클릭 시 호출 -> 그 순간 버퍼를 샘플로 채택 */
    private fun onUserConfirmedTapGesture() {
        val samples = motionBuffer.snapshot()
        val result = GestureMatcher.evaluate(samples, baseline = null)
        collectedDisplacements.add(result.netDisplacement)
        collectedOrientations.add(result.orientationShift)
        // 사용자가 직접 확인한 정상 태그 샘플 -> 서버 학습 데이터(정상 클래스)로 업로드
        AnomalyReporter.uploadOnboardingSampleAsync(applicationContext, result, samples)

        renderProgress()
        if (collectedDisplacements.size >= MIN_SAMPLES) {
            finishOnboarding()
        }
    }

    private fun renderProgress() {
        val count = collectedDisplacements.size
        binding.progressText.text = getString(R.string.onboarding_progress, count, MIN_SAMPLES)
        binding.progressBar.setProgressCompat(count, true)
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
        Toast.makeText(this, R.string.onboarding_done, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        // 보호가 꺼진 상태에서 등록만 하러 들어온 경우 센서를 다시 끔
        if (!ProtectionSettings.isEnabled(this)) motionBuffer.stop()
        super.onDestroy()
    }

    companion object {
        private const val MIN_SAMPLES = 6
    }
}
