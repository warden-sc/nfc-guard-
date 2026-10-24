package com.nfcguard.app

import android.app.Application

class NfcGuardApp : Application() {
    lateinit var motionBuffer: MotionBuffer
        private set
    lateinit var deviceContextMonitor: DeviceContextMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        motionBuffer = MotionBuffer(this)
        motionBuffer.start()
        deviceContextMonitor = DeviceContextMonitor(this)
        deviceContextMonitor.start()
        ModelUpdater.loadInstalled(this)
        // 백그라운드 상시 수집은 MotionForegroundService가 담당 (MainActivity/BootReceiver에서 시작).
        // 제조사별 배터리 최적화(강제 종료)가 심한 기종은 최적화 예외 안내가 추가로 필요할 수 있음.
    }
}
