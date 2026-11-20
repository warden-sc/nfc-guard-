package com.nfcguard.app

import android.content.Context

/** 사용자가 메인 화면 스위치로 켜고 끄는 보호 상태 (기본: 켜짐) */
object ProtectionSettings {
    private const val PREFS = "nfcguard_settings"
    private const val KEY_ENABLED = "protection_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
