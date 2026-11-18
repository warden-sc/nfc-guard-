package com.nfcguard.app

import android.app.Application

class NfcGuardApp : Application() {
    lateinit var motionBuffer: MotionBuffer
        private set
    lateinit var deviceContextMonitor: DeviceContextMonitor
        private set
    lateinit var movementInput: AndroidMovementInput
        private set

    override fun onCreate() {
        super.onCreate()
        motionBuffer = MotionBuffer(this)
        deviceContextMonitor = DeviceContextMonitor(this)
        movementInput = AndroidMovementInput(this)
        if (ProtectionSettings.isEnabled(this)) {
            motionBuffer.start()
            deviceContextMonitor.start()
        }
        // 백그라운드 상시 수집은 MotionForegroundService가 담당 (MainActivity/BootReceiver에서 시작).
        // 제조사별 배터리 최적화(강제 종료)가 심한 기종은 최적화 예외 안내가 추가로 필요할 수 있음.
    }

    fun evaluateTap(trigger: String, detectedAtNanos: Long) {
        if (!ProtectionSettings.isEnabled(this)) return
        val movement = movementInput.snapshot(detectedAtNanos)
        motionBuffer.flushThen {
            if (!ProtectionSettings.isEnabled(this)) return@flushThen
            val verdict = TapDetectionRule.evaluate(motionBuffer.rotationSnapshot(), detectedAtNanos, movement.state)
            AnomalyReporter.recordRuleTapAsync(this, verdict, movement, trigger, detectedAtNanos)
        }
    }
}
