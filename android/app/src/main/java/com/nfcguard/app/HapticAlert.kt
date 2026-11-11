package com.nfcguard.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 진동 모터(액추에이터)로 의심 태그를 알린다.
 * 가방/주머니 속에 있어 화면을 볼 수 없는 상황이 핵심 대상이므로,
 * 일반 알림 진동과 구분되는 "짧게-짧게-길게" 패턴을 사용한다.
 */
object HapticAlert {

    // 대기, 진동, 대기, 진동, ... (ms)
    private val SUSPICIOUS_PATTERN = longArrayOf(0, 120, 80, 120, 80, 500)

    fun vibrateSuspicious(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        val effect = VibrationEffect.createWaveform(SUSPICIOUS_PATTERN, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 무음/방해금지 모드에서도 보안 경고로 전달되도록 알람 용도로 지정
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
