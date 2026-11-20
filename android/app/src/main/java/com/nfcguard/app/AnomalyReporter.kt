package com.nfcguard.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 의심 이벤트를 백엔드로 비동기 전송.
 * 절대 processCommandApdu 스레드를 블로킹하지 않도록 별도 executor 사용.
 *
 * 알림 단계:
 * - 고위험(>= IMMEDIATE_ALERT_THRESHOLD, 예: 가방 안에서 태그됨) -> 1회만 발생해도 즉시 알림 + 진동
 * - 애매한 이벤트 -> 짧은 시간창 내 N회 이상 쌓일 때만 알림 (오탐 방지)
 * 알림·진동·기기 내 기록은 네트워크·동의 여부와 무관하게 즉시. 서버 전송은 동의한 경우에만 UploadQueue로.
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
            val elapsedSinceDetectionMs = (SystemClock.elapsedRealtimeNanos() - detectedAtNanos) / 1_000_000L
            val occurredAtEpochMs = nowMs - elapsedSinceDetectionMs

            // 메인 화면 "최근 감지 기록"에 즉시 저장 (서버 id는 업로드 후, 위치는 측정 후 붙음)
            val localId = UUID.randomUUID().toString()
            EventLogStore.add(
                context,
                EventLogStore.Entry(localId, occurredAtEpochMs, result.riskScore, result.reason, shouldAlert, null, null)
            )
            LocationLogger.captureFor(context, localId)

            if (shouldAlert) {
                NotificationHelper.notifySuspiciousActivity(
                    context, NotificationHelper.newNotificationId(), result.reason, repeatCount, localId
                )
                HapticAlert.vibrateSuspicious(context)
            }

            // 3) 서버 전송 (동의한 경우만, 원본 센서 시계열 포함 -> 학습 재료). 오프라인이면 큐에서 재시도
            UploadQueue.enqueue(
                context, localId,
                eventPayload(
                    AnomalyEventSource.FIELD_DETECTION, result, deviceContext, samples,
                    occurredAtEpochMs = occurredAtEpochMs, repeatCount = repeatCount
                )
            )
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
            UploadQueue.enqueue(context, null, payload)
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
            UploadQueue.enqueue(context, null, payload)
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
        result.modelVersion?.let { put("modelVersion", it) }
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
