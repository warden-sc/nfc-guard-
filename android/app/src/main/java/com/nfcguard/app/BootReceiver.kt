package com.nfcguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅 후에도 사용자가 앱을 열지 않은 상태에서 바로 보호가 시작되도록 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && ProtectionSettings.isEnabled(context)) {
            MotionForegroundService.start(context)
        }
    }
}
