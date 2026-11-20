package com.nfcguard.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 알림의 "내가 태그함" / "내가 안 함" 버튼.
 * 응답은 먼저 기기 감지 기록에 저장하고(네트워크와 무관), 서버 반영은 UploadQueue가 처리한다.
 * 이 라벨이 AI 학습의 정답 데이터가 된다 (label_source = user_response).
 */
class LabelActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LABEL) return
        val localId = intent.getStringExtra(EXTRA_LOCAL_ID) ?: return
        val wasIntentional = intent.getBooleanExtra(EXTRA_WAS_INTENTIONAL, false)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        recordLabel(context, localId, wasIntentional)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationId)
    }

    companion object {
        const val ACTION_LABEL = "com.nfcguard.app.action.LABEL_EVENT"
        const val EXTRA_LOCAL_ID = "localId"
        const val EXTRA_WAS_INTENTIONAL = "wasIntentional"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        /** 알림·메인 화면 공통 */
        fun recordLabel(context: Context, localId: String, wasIntentional: Boolean) {
            EventLogStore.setLabelLocal(context, localId, wasIntentional)
            UploadQueue.flushAsync(context)
        }
    }
}
