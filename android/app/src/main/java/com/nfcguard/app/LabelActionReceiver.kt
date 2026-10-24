package com.nfcguard.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/**
 * 알림의 "내가 태그함" / "내가 안 함" 버튼 처리 -> 서버 confirm 호출.
 * 이 라벨이 AI 파인튜닝의 정답 데이터가 된다 (사용자 확인 없이는 정답을 알 수 없음).
 */
class LabelActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LABEL) return
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (eventId <= 0) return
        val wasIntentional = intent.getBooleanExtra(EXTRA_WAS_INTENTIONAL, false)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        val pending = goAsync()
        executor.execute {
            try {
                val ok = BackendClient.confirmLabel(context.applicationContext, eventId, wasIntentional)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (ok) nm.cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LABEL = "com.nfcguard.app.action.LABEL_EVENT"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_WAS_INTENTIONAL = "wasIntentional"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
