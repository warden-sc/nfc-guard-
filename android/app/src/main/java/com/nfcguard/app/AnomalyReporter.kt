package com.nfcguard.app

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * 의심 이벤트를 카드사/백엔드로 비동기 전송.
 * 절대 processCommandApdu 스레드를 블로킹하지 않도록 별도 executor 사용.
 *
 * 알림 단계:
 * - 고위험(>= IMMEDIATE_ALERT_THRESHOLD, 예: 가방 안에서 태그됨) -> 1회만 발생해도 즉시 알림 + 진동
 * - 애매한 이벤트 -> 짧은 시간창 내 N회 이상 쌓일 때만 알림 (오탐 방지)
 */
object AnomalyReporter {

    private val executor = Executors.newSingleThreadExecutor()
    private val recentEvents = ArrayDeque<Long>() // 최근 고위험 이벤트 타임스탬프(ms)
    private const val ESCALATION_WINDOW_MS = 60_000L
    private const val ESCALATION_COUNT = 3
    private const val IMMEDIATE_ALERT_THRESHOLD = 0.7

    fun reportAsync(context: Context, riskScore: Double, reason: String, detectedAtNanos: Long) {
        executor.execute {
            val nowMs = System.currentTimeMillis()

            // 1) 로컬 반복 빈도 누적
            synchronized(recentEvents) {
                recentEvents.addLast(nowMs)
                while (recentEvents.isNotEmpty() && nowMs - recentEvents.first() > ESCALATION_WINDOW_MS) {
                    recentEvents.removeFirst()
                }
            }
            val repeatCount = synchronized(recentEvents) { recentEvents.size }

            Log.i(TAG, "async report: risk=$riskScore reason=$reason repeatCount=$repeatCount")

            // 2) TODO: 실제 백엔드/카드사 이상거래탐지(FDS) 엔드포인트로 전송
            //    - 세션 크립토그램 식별자, 위험도, 반복횟수, (동의된 경우) 대략적 위치 등을 포함
            //    - 여기서는 네트워크 클라이언트 스캐폴딩만 표시 (엔드포인트는 실제 발급사와 연동 필요)
            postToBackendStub(riskScore, reason, repeatCount)

            // 3) 고위험 단건 또는 반복 임계치 초과 -> 사용자 알림 + 진동 (트랜잭션은 이미 통과, 사후 알림)
            if (riskScore >= IMMEDIATE_ALERT_THRESHOLD || repeatCount >= ESCALATION_COUNT) {
                NotificationHelper.notifySuspiciousActivity(context, riskScore, reason, repeatCount)
                HapticAlert.vibrateSuspicious(context)
            }
        }
    }

    private fun postToBackendStub(riskScore: Double, reason: String, repeatCount: Int) {
        // 실제 구현 시 OkHttp/Retrofit + mTLS로 교체
        // val body = """{"risk":$riskScore,"reason":"$reason","repeat":$repeatCount}"""
        // client.newCall(request).execute()
    }

    private const val TAG = "AnomalyReporter"
}
