package com.nfcguard.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

class NfcGuardApp : Application() {
    lateinit var motionBuffer: MotionBuffer
        private set
    lateinit var deviceContextMonitor: DeviceContextMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        motionBuffer = MotionBuffer(this)
        deviceContextMonitor = DeviceContextMonitor(this)
        if (ProtectionSettings.isEnabled(this)) {
            motionBuffer.start()
            deviceContextMonitor.start()
        }
        ModelUpdater.loadInstalled(this)
        // 네트워크가 연결되면 오프라인 동안 쌓인 이벤트·라벨 전송
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = UploadQueue.flushAsync(this@NfcGuardApp)
            })
        // 백그라운드 상시 수집은 MotionForegroundService가 담당 (MainActivity/BootReceiver에서 시작).
        // 제조사별 배터리 최적화(강제 종료)가 심한 기종은 최적화 예외 안내가 추가로 필요할 수 있음.
    }
}
