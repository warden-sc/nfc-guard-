package com.nfcguard.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 알림의 "내가 태그함" / "내가 안 함" 버튼 처리 -> 서버 confirm 호출.
 * 이 라벨이 AI 파인튜닝의 정답 데이터가 된다 (사용자 확인 없이는 정답을 알 수 없음).
 * 메인 화면 감지 기록의 같은 버튼도 submitAsync()를 공유한다.
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
                if (submit(context.applicationContext, eventId, wasIntentional)) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notificationId)
                }
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
        private val mainHandler = Handler(Looper.getMainLooper())

        /** 화면에서 호출: 백그라운드로 전송 후 결과를 메인 스레드 콜백으로 */
        fun submitAsync(context: Context, eventId: Long, wasIntentional: Boolean, onDone: (Boolean) -> Unit) {
            val app = context.applicationContext
            executor.execute {
                val ok = submit(app, eventId, wasIntentional)
                mainHandler.post { onDone(ok) }
            }
        }

        private fun submit(context: Context, eventId: Long, wasIntentional: Boolean): Boolean {
            val ok = BackendClient.confirmLabel(context, eventId, wasIntentional)
            if (ok) EventLogStore.setLabel(context, eventId, wasIntentional)
            return ok
        }
    }
}
