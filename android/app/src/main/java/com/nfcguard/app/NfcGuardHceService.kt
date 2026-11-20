package com.nfcguard.app

import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlin.random.Random

/**
 * 중요: 이 서비스는 실제 결제/교통카드 AID를 직접 처리하지 않는다.
 * (그건 이미 OS/Wallet/카드 발급사 앱이 담당 - 여기서 가로채면 실결제가 깨짐)
 *
 * 이 서비스가 하는 일은 "필드가 감지된 시점"이라는 이벤트 자체를 잡아서
 * MotionBuffer 스냅샷을 조회 -> 위험도 스코어 계산 -> 비동기 리포트만 남기는 것.
 * processCommandApdu는 원래 흐름을 방해하지 않도록 즉시 통과 응답을 돌려준다.
 *
 * 실제 프로덕션에서는 이 서비스가 결제 AID와 "병렬로" 등록되어 필드 감지 브로드캐스트만
 * 수신하는 구조(또는 NfcAdapter.ReaderCallback 활용)로 가야 하며,
 * 여기서는 로직 검증용 스캐폴딩으로 processCommandApdu 후킹 지점을 그대로 보여준다.
 *
 * Android 15+(API 35)에서는 processPollingFrames()로 리더기 폴링(필드 ON)을 결제 응답 이전에
 * 관찰할 수 있다 (Observe Mode / polling loop filter). 두 경로 모두 onFieldDetected()로 합류한다.
 * 주의: 서드파티 앱이 "다른 서비스로 가는" 폴링까지 받는지는 기종/NFC 컨트롤러별로 다르므로
 * 실기기 검증 전에는 실험 기능으로 취급할 것.
 */
class NfcGuardHceService : HostApduService() {

    private lateinit var motionBuffer: MotionBuffer
    private lateinit var deviceContextMonitor: DeviceContextMonitor
    private var baseline: UserGestureBaseline? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as NfcGuardApp
        motionBuffer = app.motionBuffer
        deviceContextMonitor = app.deviceContextMonitor
        baseline = GestureBaselineStore.load(applicationContext)
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        onFieldDetected("apdu")
        // 실제 결제 응답은 OS/Wallet이 처리하므로, 여기서는 통과용 더미 응답만 반환.
        return SELECT_OK_SW
    }

    /** Android 15+: 리더기 폴링 프레임 관찰. 필드 ON 프레임 = 리더기에 닿은 순간 */
    override fun processPollingFrames(frames: List<PollingFrame>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (frames.any { it.type == PollingFrame.POLLING_LOOP_TYPE_ON }) {
            onFieldDetected("polling_loop")
        }
    }

    /**
     * 필드 감지 1회당 판정 1회. 태그 한 번에 APDU가 여러 개 오고(SELECT, READ ...)
     * 폴링 프레임도 초당 여러 번 오므로 디바운스하지 않으면 같은 태그가 여러 번 신고되어
     * 반복 카운트(AnomalyReporter 에스컬레이션)가 부풀려진다.
     */
    private fun onFieldDetected(trigger: String) {
        if (!ProtectionSettings.isEnabled(applicationContext)) return
        // SensorEvent.timestamp와 같은 시계(부팅 후 경과시간)를 써야 버퍼와 시점 비교가 맞음
        val fieldDetectedAtNanos = SystemClock.elapsedRealtimeNanos()
        synchronized(Companion) {
            if (fieldDetectedAtNanos - lastFieldDetectedAtNanos < FIELD_DEBOUNCE_NANOS) return
            lastFieldDetectedAtNanos = fieldDetectedAtNanos
        }

        // 1) 주변상태(근접/조도/화면)는 지금 즉시 고정
        val deviceContext = deviceContextMonitor.snapshot()

        // 2) 화면 꺼짐 중엔 센서값이 하드웨어 FIFO에 남아 있으므로 flush 후 판정.
        //    판정/신고는 사후 알림용이라 응답(아래 return)을 기다리게 하지 않는다.
        motionBuffer.flushThen {
            val samples = motionBuffer.snapshot().filter { it.tNanos <= fieldDetectedAtNanos }
            val heuristic = GestureMatcher.evaluate(samples, baseline, deviceContext)
            // 학습된 온디바이스 모델이 있으면 점수를 섞음 (없거나 실패하면 휴리스틱 단독)
            val result = ModelUpdater.current?.let { model ->
                runCatching { RiskBlender.blend(heuristic, model.predictIntentionalProb(samples), model.spec) }
                    .getOrNull()
            } ?: heuristic

            Log.d(TAG, "field detected via $trigger, risk=${result.riskScore} reason=${result.reason} pocket=${result.pocketScore}")

            // 3) 트랜잭션은 절대 막지 않음. 위험도가 높아도 정상 응답을 그대로 통과.
            //    대신 비동기로 서버에 신고 -> 카드사/발급 서버 측 사후 대응(세션 플래그) 트리거
            if (result.riskScore > RISK_REPORT_THRESHOLD) {
                AnomalyReporter.reportAsync(
                    applicationContext,
                    result = result,
                    deviceContext = deviceContext,
                    samples = samples,
                    detectedAtNanos = fieldDetectedAtNanos
                )
            } else if (Random.nextDouble() < NORMAL_SAMPLE_UPLOAD_RATE) {
                // 정상 판정 이벤트도 일부 업로드: 의심 이벤트만 모이면 학습 데이터가 한쪽으로 치우침
                AnomalyReporter.uploadNormalSampleAsync(applicationContext, result, deviceContext, samples, fieldDetectedAtNanos)
            }
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "deactivated, reason=$reason")
    }

    companion object {
        private const val TAG = "NfcGuardHce"
        private const val RISK_REPORT_THRESHOLD = 0.4
        private const val NORMAL_SAMPLE_UPLOAD_RATE = 0.05
        private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00)
        private const val FIELD_DEBOUNCE_NANOS = 3_000_000_000L // 3초

        // 서비스 인스턴스가 재생성돼도 유지되도록 companion에 둠
        private var lastFieldDetectedAtNanos = Long.MIN_VALUE / 2
    }
}
