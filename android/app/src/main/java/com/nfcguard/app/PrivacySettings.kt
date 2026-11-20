package com.nfcguard.app

import android.content.Context

/**
 * 개인정보 관련 사용자 선택.
 * - dataSharingConsent: 서버로 감지 이벤트(센서 시계열 포함)·라벨을 보내는 데 동의했는지. null = 아직 묻지 않음
 *   동의하지 않아도 감지·알림·기기 내 기록은 그대로 동작한다.
 * - locationLogEnabled: 감지 기록에 위치를 남길지 (기기 안에만 저장, 서버로 보내지 않음)
 */
object PrivacySettings {
    private const val PREFS = "nfcguard_privacy"
    private const val KEY_CONSENT = "data_sharing_consent"
    private const val KEY_LOCATION = "location_log_enabled"

    fun dataSharingConsent(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_CONSENT)) prefs.getBoolean(KEY_CONSENT, false) else null
    }

    fun canUpload(context: Context): Boolean = dataSharingConsent(context) == true

    fun setDataSharingConsent(context: Context, consent: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONSENT, consent).apply()
    }

    fun locationLogEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCATION, false)

    fun setLocationLogEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOCATION, enabled).apply()
    }
}
