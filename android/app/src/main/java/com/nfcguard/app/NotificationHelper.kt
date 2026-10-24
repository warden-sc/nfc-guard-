package com.nfcguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {
    private const val CHANNEL_ID = "nfcguard_alerts"
    private val nextNotificationId = AtomicInteger(2000)

    fun newNotificationId(): Int = nextNotificationId.getAndIncrement()

    /**
     * 의심 태그 알림. 감지 즉시 eventId 없이 한 번 띄우고,
     * 서버 업로드가 끝나 eventId를 받으면 같은 notificationId로 다시 호출해 라벨 버튼을 붙인다.
     */
    fun notifySuspiciousActivity(
        context: Context,
        notificationId: Int,
        reason: String,
        repeatCount: Int,
        eventId: Long? = null
    ) {
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
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("본인 확인 안 된 NFC 접촉 감지")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true) // 버튼 붙이려고 갱신할 때 다시 울리지 않게
            .setAutoCancel(true)

        if (eventId != null) {
            builder.addAction(0, "내가 태그함", labelIntent(context, notificationId, eventId, wasIntentional = true))
            builder.addAction(0, "내가 안 함", labelIntent(context, notificationId, eventId, wasIntentional = false))
        }
        nm.notify(notificationId, builder.build())
    }

    private fun labelIntent(context: Context, notificationId: Int, eventId: Long, wasIntentional: Boolean): PendingIntent {
        val intent = Intent(context, LabelActionReceiver::class.java).apply {
            action = LabelActionReceiver.ACTION_LABEL
            putExtra(LabelActionReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(LabelActionReceiver.EXTRA_WAS_INTENTIONAL, wasIntentional)
            putExtra(LabelActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        // requestCode를 버튼마다 다르게 해야 extras가 서로 덮어써지지 않음
        val requestCode = notificationId * 2 + if (wasIntentional) 1 else 0
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
