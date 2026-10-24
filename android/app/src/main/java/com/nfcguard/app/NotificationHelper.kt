package com.nfcguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "nfcguard_alerts"
    private const val NOTIF_ID = 1001

    fun notifySuspiciousActivity(context: Context, riskScore: Double, reason: String, repeatCount: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return // POST_NOTIFICATIONS 미허용 시 진동(HapticAlert)만 전달됨
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NFC 이상감지", NotificationManager.IMPORTANCE_HIGH).apply {
                    // 진동은 HapticAlert가 전용 패턴으로 직접 울리므로 채널 진동은 끔 (이중 진동 방지)
                    enableVibration(false)
                }
            )
        }
        val text = when (reason) {
            "tapped_while_in_bag_or_pocket" ->
                "기기가 가방/주머니 안에 있는 상태에서 NFC 태그가 감지되었습니다. 카드사 거래내역을 확인하세요."
            "no_intentional_gesture_detected" ->
                "기기를 꺼내 태그하는 동작 없이 NFC 태그가 감지되었습니다. 카드사 거래내역을 확인하세요."
            else ->
                "최근 1분 내 ${repeatCount}회, 의도적 동작이 불확실한 NFC 응답이 발생했습니다. 카드사 거래내역을 확인하세요."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("본인 확인 안 된 NFC 접촉 감지")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notification)
    }
}
