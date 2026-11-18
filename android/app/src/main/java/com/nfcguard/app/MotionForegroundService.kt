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
import android.util.Log
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
    private val sensorServiceType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

    override fun onCreate() {
        super.onCreate()
        val notification = buildOngoingNotification()
        ServiceCompat.startForeground(this, ONGOING_NOTIF_ID, notification, sensorServiceType)

        val app = application as NfcGuardApp
        app.motionBuffer.start()
        app.deviceContextMonitor.start()

        // 하드웨어 FIFO가 작은 기종은 화면 꺼짐 중 데이터가 끊기므로 CPU를 깨워둔다 (배터리 비용 있음)
        if (!app.motionBuffer.hasSufficientFifo()) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcGuard:motion").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ProtectionSettings.isEnabled(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_LOCATION, false) == true && AndroidMovementInput.hasFineLocation(this)) {
            try {
                ServiceCompat.startForeground(
                    this, ONGOING_NOTIF_ID, buildOngoingNotification(),
                    sensorServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                (application as NfcGuardApp).movementInput.start()
            } catch (e: SecurityException) {
                Log.w("NfcGuardMotion", "Location service unavailable", e)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        val app = application as NfcGuardApp
        app.movementInput.stop()
        app.motionBuffer.stop()
        app.deviceContextMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildOngoingNotification() = run {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_monitor), NotificationManager.IMPORTANCE_MIN)
            )
        }
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(getString(R.string.notif_monitor_title))
            .setContentText(getString(R.string.notif_monitor_text))
            .setContentIntent(NotificationHelper.openAppIntent(this))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nfcguard_monitor"
        private const val ONGOING_NOTIF_ID = 1000
        private const val EXTRA_LOCATION = "start_location"

        fun start(context: Context, withLocation: Boolean = false) {
            val intent = Intent(context, MotionForegroundService::class.java).putExtra(EXTRA_LOCATION, withLocation)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MotionForegroundService::class.java))
        }
    }
}
