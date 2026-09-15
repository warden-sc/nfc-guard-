package com.nfcguard.app

import android.app.Application

class NfcGuardApp : Application() {
    lateinit var motionBuffer: MotionBuffer
        private set

    override fun onCreate() {
        super.onCreate()
        motionBuffer = MotionBuffer(this)
        motionBuffer.start()
        // 실제 배포 시: 배터리 최적화 화이트리스트 안내 + Foreground Service로 승격 권장
        // (센서 상시 수집이 Doze 모드에서 중단되지 않도록)
    }
}
