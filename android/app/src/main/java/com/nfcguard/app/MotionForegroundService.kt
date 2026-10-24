package com.nfcguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 센서 상시 수집을 위한 Foreground Service.
 * Android 9+는 백그라운드 앱에 가속도/자이로/근접 이벤트를 주지 않으므로,
 * 이 서비스가 떠 있어야 "가방 안에서 태그된 순간 직전 2.5초"가 버퍼에 남아 있다.
 */
class MotionForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildOngoingNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, ONGOING_NOTIF_ID, notification, type)

        val app = application as NfcGuardApp
        app.motionBuffer.start()
        app.deviceContextMonitor.start()
        ModelUpdater.schedulePeriodicCheck(this)

        // 하드웨어 FIFO가 작은 기종은 화면 꺼짐 중 데이터가 끊기므로 CPU를 깨워둔다 (배터리 비용 있음)
        if (!app.motionBuffer.hasSufficientFifo()) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcGuard:motion").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildOngoingNotification() = run {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NFC 보호 실행 중", NotificationManager.IMPORTANCE_MIN)
            )
        }
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("NFC Guard 보호 중")
            .setContentText("의도하지 않은 NFC 태그를 감시하고 있습니다.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nfcguard_monitor"
        private const val ONGOING_NOTIF_ID = 1000

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MotionForegroundService::class.java))
        }
    }
}
