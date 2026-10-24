package com.nfcguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 거부돼도 진동 경고는 동작 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_main)
        // 버튼: "내 태깅 패턴 등록하기" -> OnboardingActivity
        // 리스트: 최근 이상감지 로그 (AnomalyReporter 로컬 로그 조회)
        requestNotificationPermissionIfNeeded()
        MotionForegroundService.start(this)
    }

    /** Android 13+는 알림 런타임 권한이 없으면 의심 태그 알림이 표시되지 않음 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
    }
}
