package com.nfcguard.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_main)
        // 버튼: "내 태깅 패턴 등록하기" -> OnboardingActivity
        // 리스트: 최근 이상감지 로그 (AnomalyReporter 로컬 로그 조회)
    }

    private fun openOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
    }
}
