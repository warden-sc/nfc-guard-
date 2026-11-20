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
        promote(allowLocation = false) // 5초 안에 startForeground 필수. 위치 타입은 onStartCommand에서 판단

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 위치 기록이 켜져 있고 권한이 있으면 location 타입을 함께 지정해야 백그라운드에서 위치를 측정할 수 있다.
        // Android 14+는 백그라운드(부팅 직후 등)에서 location 타입 FGS를 시작할 수 없으므로 부팅 시작은 제외 -
        // 이후 사용자가 앱을 열면 MainActivity가 다시 start()를 호출해 위치 타입이 붙는다.
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        val wantLocation = !fromBoot && PrivacySettings.locationLogEnabled(this) && LocationLogger.hasPermission(this)
        promote(wantLocation)
        UploadQueue.flushAsync(this)
        return START_STICKY
    }

    private fun promote(allowLocation: Boolean) {
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (allowLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else 0
            ServiceCompat.startForeground(this, ONGOING_NOTIF_ID, notification, type)
            return
        }
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            val type = if (allowLocation) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base
            ServiceCompat.startForeground(this, ONGOING_NOTIF_ID, notification, type)
        } catch (e: SecurityException) {
            // 위치 권한이 회수됐거나 시작 조건을 못 맞춘 경우: 감지는 계속, 위치만 측정 불가(no_permission 등으로 기록)
            ServiceCompat.startForeground(this, ONGOING_NOTIF_ID, notification, base)
        }
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        // 사용자가 보호를 끈 경우 센서 수집도 중단 (배터리)
        if (!ProtectionSettings.isEnabled(this)) {
            val app = application as NfcGuardApp
            app.motionBuffer.stop()
            app.deviceContextMonitor.stop()
        }
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
        private const val EXTRA_FROM_BOOT = "fromBoot"

        fun start(context: Context, fromBoot: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MotionForegroundService::class.java).putExtra(EXTRA_FROM_BOOT, fromBoot)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MotionForegroundService::class.java))
        }
    }
}
