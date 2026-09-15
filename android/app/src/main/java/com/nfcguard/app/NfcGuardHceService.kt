package com.nfcguard.app

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

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
 */
class NfcGuardHceService : HostApduService() {

    private lateinit var motionBuffer: MotionBuffer
    private var baseline: UserGestureBaseline? = null

    override fun onCreate() {
        super.onCreate()
        motionBuffer = (application as NfcGuardApp).motionBuffer
        baseline = GestureBaselineStore.load(applicationContext)
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val fieldDetectedAtNanos = System.nanoTime()

        // 1) 이미 상시 수집 중인 버퍼를 즉시 조회 (새로 측정 X -> 지연 없음)
        val samples = motionBuffer.snapshot()

        // 2) 판정 (수 ms 내 완료, 트랜잭션 타임아웃 예산에 영향 없음)
        val result = GestureMatcher.evaluate(samples, baseline)

        Log.d(TAG, "field detected, risk=${result.riskScore} reason=${result.reason}")

        // 3) 트랜잭션은 절대 막지 않음. 위험도가 높아도 정상 응답을 그대로 통과.
        //    대신 비동기로 서버에 신고 -> 카드사/발급 서버 측 사후 대응(세션 플래그) 트리거
        if (result.riskScore > RISK_REPORT_THRESHOLD) {
            AnomalyReporter.reportAsync(
                applicationContext,
                riskScore = result.riskScore,
                reason = result.reason,
                detectedAtNanos = fieldDetectedAtNanos
            )
        }

        // 실제 결제 응답은 OS/Wallet이 처리하므로, 여기서는 통과용 더미 응답만 반환.
        return SELECT_OK_SW
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "deactivated, reason=$reason")
    }

    companion object {
        private const val TAG = "NfcGuardHce"
        private const val RISK_REPORT_THRESHOLD = 0.4
        private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00)
    }
}
