package com.nfcguard.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 의심 이벤트를 백엔드로 비동기 전송.
 * 절대 processCommandApdu 스레드를 블로킹하지 않도록 별도 executor 사용.
 *
 * 알림 단계:
 * - 고위험(>= IMMEDIATE_ALERT_THRESHOLD, 예: 가방 안에서 태그됨) -> 1회만 발생해도 즉시 알림 + 진동
 * - 애매한 이벤트 -> 짧은 시간창 내 N회 이상 쌓일 때만 알림 (오탐 방지)
 * 알림은 네트워크를 기다리지 않고 먼저 띄우고, 업로드가 끝나면 라벨 버튼을 붙인다.
 */
object AnomalyReporter {

    private val executor = Executors.newSingleThreadExecutor()
    private val recentEvents = ArrayDeque<Long>() // 최근 고위험 이벤트 타임스탬프(ms)
    private const val ESCALATION_WINDOW_MS = 60_000L
    private const val ESCALATION_COUNT = 3
    private const val IMMEDIATE_ALERT_THRESHOLD = 0.7

    fun reportAsync(
        context: Context,
        result: GestureMatcher.Result,
        deviceContext: DeviceContext,
        samples: List<MotionSample>,
        detectedAtNanos: Long
    ) {
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

            Log.i(TAG, "async report: risk=${result.riskScore} reason=${result.reason} repeatCount=$repeatCount")

            // 2) 고위험 단건 또는 반복 임계치 초과 -> 사용자 알림 + 진동 (트랜잭션은 이미 통과, 사후 알림)
            val shouldAlert = result.riskScore >= IMMEDIATE_ALERT_THRESHOLD || repeatCount >= ESCALATION_COUNT
            val notificationId = if (shouldAlert) NotificationHelper.newNotificationId() else 0
            if (shouldAlert) {
                NotificationHelper.notifySuspiciousActivity(context, notificationId, result.reason, repeatCount)
                HapticAlert.vibrateSuspicious(context)
            }

            // 3) 백엔드 전송 (원본 센서 시계열 포함 -> 파인튜닝 재료)
            //    TODO: 오프라인일 때 유실되지 않도록 로컬 재전송 큐 추가
            val elapsedSinceDetectionMs = (SystemClock.elapsedRealtimeNanos() - detectedAtNanos) / 1_000_000L
            val payload = eventPayload(
                AnomalyEventSource.FIELD_DETECTION, result, deviceContext, samples,
                occurredAtEpochMs = nowMs - elapsedSinceDetectionMs, repeatCount = repeatCount
            )
            val eventId = BackendClient.postEvent(context, payload)

            // 4) 업로드 성공 시 같은 알림에 "내가 태그함 / 내가 안 함" 라벨 버튼 추가
            if (shouldAlert && eventId != null) {
                NotificationHelper.notifySuspiciousActivity(context, notificationId, result.reason, repeatCount, eventId)
            }
        }
    }

    /** 정상 판정 이벤트 샘플링 업로드 (알림/반복 카운트 없음, 라벨은 미확정 상태로 저장) */
    fun uploadNormalSampleAsync(
        context: Context,
        result: GestureMatcher.Result,
        deviceContext: DeviceContext,
        samples: List<MotionSample>,
        detectedAtNanos: Long
    ) {
        executor.execute {
            val elapsedSinceDetectionMs = (SystemClock.elapsedRealtimeNanos() - detectedAtNanos) / 1_000_000L
            val payload = eventPayload(
                AnomalyEventSource.FIELD_DETECTION, result, deviceContext, samples,
                occurredAtEpochMs = System.currentTimeMillis() - elapsedSinceDetectionMs, repeatCount = 0
            )
            BackendClient.postEvent(context, payload)
        }
    }

    /** 온보딩에서 사용자가 직접 태그한 샘플 -> 서버에서 정상(의도적) 라벨로 바로 편입 */
    fun uploadOnboardingSampleAsync(context: Context, result: GestureMatcher.Result, samples: List<MotionSample>) {
        executor.execute {
            val ctx = (context.applicationContext as NfcGuardApp).deviceContextMonitor.snapshot()
            val payload = eventPayload(
                AnomalyEventSource.ONBOARDING, result, ctx, samples,
                occurredAtEpochMs = System.currentTimeMillis(), repeatCount = 0
            )
            BackendClient.postEvent(context, payload)
        }
    }

    private fun eventPayload(
        source: String,
        result: GestureMatcher.Result,
        ctx: DeviceContext,
        samples: List<MotionSample>,
        occurredAtEpochMs: Long,
        repeatCount: Int
    ) = JSONObject().apply {
        put("source", source)
        put("riskScore", result.riskScore)
        put("reason", result.reason)
        put("repeatCount", repeatCount)
        put("occurredAtEpochMs", occurredAtEpochMs)
        put("netDisplacement", result.netDisplacement)
        put("orientationShift", result.orientationShift)
        put("pocketScore", result.pocketScore)
        ctx.proximityNear?.let { put("proximityNear", it) }
        ctx.ambientLux?.let { put("ambientLux", it.toDouble()) }
        put("screenInteractive", ctx.screenInteractive)
        put("rawSensorWindow", BackendClient.sensorWindowJson(samples))
    }

    private const val TAG = "AnomalyReporter"
}

/** server-java AnomalyEvent.SOURCE_* 와 동일한 값 */
object AnomalyEventSource {
    const val FIELD_DETECTION = "field_detection"
    const val ONBOARDING = "onboarding"
}
