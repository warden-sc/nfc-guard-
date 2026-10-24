package com.nfcguard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
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
        logNfcCapabilities()
    }

    /**
     * 실기기 검증용: 이 기종이 Observe Mode(폴링 프레임 관찰)를 지원하는지.
     * 미지원 기종은 processCommandApdu 경로(데모 AID)만 동작 -> 실제 스키밍 탐지 범위가 제한됨.
     */
    private fun logNfcCapabilities() {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: run {
            Log.w(TAG, "NFC 없음")
            return
        }
        val observeMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            adapter.isObserveModeSupported
        } else false
        Log.i(TAG, "NFC enabled=${adapter.isEnabled} observeModeSupported=$observeMode sdk=${Build.VERSION.SDK_INT}")
        // TODO: 화면 UI에 "이 기기의 탐지 수준" 표시 (Observe Mode 지원 여부에 따라 안내 문구 분기)
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

    companion object {
        private const val TAG = "NfcGuardMain"
    }
}
