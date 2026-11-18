package com.nfcguard.app

import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/**
 * 중요: 이 서비스는 실제 결제/교통카드 AID를 직접 처리하지 않는다.
 * (그건 이미 OS/Wallet/카드 발급사 앱이 담당 - 여기서 가로채면 실결제가 깨짐)
 *
 * 앱에 전달된 이벤트 시각을 기준으로 위치·자이로 규칙을 평가하고 결과를 기기에 기록한다.
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
     * 감지 기록과 알림이 중복된다.
     */
    private fun onFieldDetected(trigger: String) {
        if (!ProtectionSettings.isEnabled(applicationContext)) return
        // SensorEvent.timestamp와 같은 시계(부팅 후 경과시간)를 써야 버퍼와 시점 비교가 맞음
        val fieldDetectedAtNanos = SystemClock.elapsedRealtimeNanos()
        synchronized(Companion) {
            if (fieldDetectedAtNanos - lastFieldDetectedAtNanos < FIELD_DEBOUNCE_NANOS) return
            lastFieldDetectedAtNanos = fieldDetectedAtNanos
        }

        (application as NfcGuardApp).evaluateTap(trigger, fieldDetectedAtNanos)
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "deactivated, reason=$reason")
    }

    companion object {
        private const val TAG = "NfcGuardHce"
        private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00)
        private const val FIELD_DEBOUNCE_NANOS = 3_000_000_000L // 3초

        // 서비스 인스턴스가 재생성돼도 유지되도록 companion에 둠
        private var lastFieldDetectedAtNanos = Long.MIN_VALUE / 2
    }
}
