package com.nfcguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "nfcguard_alerts"
    private const val NOTIF_ID = 1001

    fun notifySuspiciousActivity(context: Context, repeatCount: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NFC 이상감지", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("본인 확인 안 된 NFC 접촉 감지")
            .setContentText("최근 1분 내 ${repeatCount}회, 의도적 동작 없이 필드 응답이 발생했습니다. 카드사 거래내역을 확인하세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notification)
    }
}
