package com.nfcguard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nfcguard.app.databinding.ActivityMainBinding
import com.nfcguard.app.databinding.ItemCheckRowBinding
import com.nfcguard.app.databinding.ItemEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 메인 화면: 보호 상태/스위치, 기기 감지 수준, 설정 체크리스트, 최근 감지 기록, AI 모델 상태.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val timeFormat = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 거부돼도 진동 경고는 동작. 다시 묻지 못하는 상태면 설정 화면으로 안내
            if (!granted) openAppNotificationSettings()
            refresh()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            PrivacySettings.setLocationLogEnabled(this, granted)
            if (granted) restartProtectionService()
            refresh()
        }

    // 감지 기록/온보딩 baseline이 바뀌면(알림 버튼으로 라벨링 등) 화면 갱신
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.protectionSwitch.isChecked = ProtectionSettings.isEnabled(this)
        binding.protectionSwitch.setOnCheckedChangeListener { _, checked ->
            ProtectionSettings.setEnabled(this, checked)
            if (checked) MotionForegroundService.start(this) else MotionForegroundService.stop(this)
            refresh()
        }

        askConsentIfNeeded()
        if (!hasNotificationPermission()) requestNotificationPermission()
        if (ProtectionSettings.isEnabled(this)) MotionForegroundService.start(this)
        logNfcCapabilities()
    }

    override fun onResume() {
        super.onResume()
        EventLogStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        GestureBaselineStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        refresh() // 설정 화면(NFC/배터리/알림)에서 돌아왔을 때 체크리스트 반영
        UploadQueue.flushAsync(this)
    }

    override fun onPause() {
        EventLogStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        GestureBaselineStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun refresh() {
        renderStatus()
        renderChecklist()
        renderHistory()
        renderPrivacy()
        val model = ModelUpdater.current
        binding.modelStatus.text = if (model != null) {
            getString(R.string.model_active, model.spec.version.toString())
        } else getString(R.string.model_inactive)
    }

    private fun renderStatus() {
        val enabled = ProtectionSettings.isEnabled(this)
        binding.statusTitle.setText(if (enabled) R.string.status_on_title else R.string.status_off_title)
        binding.statusDesc.setText(if (enabled) R.string.status_on_desc else R.string.status_off_desc)
        ImageViewCompat.setImageTintList(binding.statusIcon, colorList(if (enabled) R.color.nfc_primary else R.color.nfc_off))
        binding.statusBadge.alpha = if (enabled) 1f else 0.5f

        val adapter = NfcAdapter.getDefaultAdapter(this)
        binding.detectionLevel.setText(
            when {
                adapter == null -> R.string.level_no_nfc
                !adapter.isEnabled -> R.string.level_nfc_off
                isObserveModeSupported(adapter) -> R.string.level_advanced
                else -> R.string.level_basic
            }
        )
    }

    private fun renderChecklist() {
        val adapter = NfcAdapter.getDefaultAdapter(this)

        bindRow(
            binding.rowNotifications, R.string.check_notifications, getString(R.string.check_notifications_desc),
            ok = hasNotificationPermission(), actionText = R.string.action_allow
        ) { requestNotificationPermission() }

        binding.rowNfc.root.visibility = if (adapter == null) View.GONE else View.VISIBLE
        bindRow(
            binding.rowNfc, R.string.check_nfc, getString(R.string.check_nfc_desc),
            ok = adapter?.isEnabled == true, actionText = R.string.action_open_settings
        ) { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        bindRow(
            binding.rowBattery, R.string.check_battery, getString(R.string.check_battery_desc),
            ok = pm.isIgnoringBatteryOptimizations(packageName), actionText = R.string.action_open_settings
        ) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

        val baseline = GestureBaselineStore.load(this)
        bindRow(
            binding.rowOnboarding, R.string.check_onboarding,
            if (baseline != null) getString(R.string.check_onboarding_done, baseline.sampleCount)
            else getString(R.string.check_onboarding_desc),
            ok = baseline != null,
            actionText = if (baseline != null) R.string.action_redo else R.string.action_register,
            showActionWhenOk = true
        ) { startActivity(Intent(this, OnboardingActivity::class.java)) }
    }

    private fun bindRow(
        row: ItemCheckRowBinding,
        titleRes: Int,
        desc: String,
        ok: Boolean,
        actionText: Int,
        showActionWhenOk: Boolean = false,
        action: () -> Unit
    ) {
        row.checkTitle.setText(titleRes)
        row.checkDesc.text = desc
        row.checkIcon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error)
        ImageViewCompat.setImageTintList(row.checkIcon, colorList(if (ok) R.color.nfc_ok else R.color.nfc_warning))
        row.checkAction.visibility = if (!ok || showActionWhenOk) View.VISIBLE else View.GONE
        row.checkAction.setText(actionText)
        row.checkAction.setOnClickListener { action() }
    }

    private fun renderHistory() {
        val entries = EventLogStore.list(this)
        binding.historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.historyList.removeAllViews()
        entries.take(MAX_HISTORY_ITEMS).forEach { entry ->
            val item = ItemEventBinding.inflate(layoutInflater, binding.historyList, false)
            bindEvent(item, entry)
            binding.historyList.addView(item.root)
        }
    }

    private fun bindEvent(item: ItemEventBinding, entry: EventLogStore.Entry) {
        val (reasonRes, colorRes) = when (entry.reason) {
            "tapped_while_in_bag_or_pocket" -> R.string.reason_bag to R.color.nfc_danger
            "no_intentional_gesture_detected" -> R.string.reason_no_gesture to R.color.nfc_danger
            "ambiguous_low_confidence" -> R.string.reason_ambiguous to R.color.nfc_warning
            else -> R.string.reason_normal to R.color.nfc_ok
        }
        item.eventReason.setText(reasonRes)
        ImageViewCompat.setImageTintList(item.eventIcon, colorList(colorRes))
        item.eventRisk.setTextColor(ContextCompat.getColor(this, colorRes))
        item.eventRisk.text = getString(R.string.risk_percent, (entry.riskScore * 100).toInt())
        item.eventTime.text = timeFormat.format(Date(entry.occurredAtMs))

        item.eventLocation.text = locationText(entry.location)
        val loc = entry.location
        if (loc?.latitude != null && loc.longitude != null) {
            item.eventLocation.setOnClickListener { openMap(loc.latitude, loc.longitude) }
        } else {
            item.eventLocation.setOnClickListener(null)
        }

        // 응답은 기기에 바로 저장 (서버 반영은 동의한 경우 UploadQueue가 처리)
        item.eventLabelButtons.visibility = if (entry.label == null) View.VISIBLE else View.GONE
        item.eventLabelState.setText(
            when {
                entry.label == true -> R.string.label_confirmed_mine
                entry.label == false -> R.string.label_confirmed_not_mine
                else -> R.string.label_question
            }
        )
        item.eventMine.setOnClickListener { recordLabel(entry.localId, wasIntentional = true) }
        item.eventNotMine.setOnClickListener { recordLabel(entry.localId, wasIntentional = false) }
    }

    private fun recordLabel(localId: String, wasIntentional: Boolean) {
        LabelActionReceiver.recordLabel(this, localId, wasIntentional) // -> prefsListener가 목록 갱신
        if (!wasIntentional) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.not_mine_dialog_title)
                .setMessage(R.string.not_mine_dialog_body)
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
    }

    /** 위치가 없으면 없음으로 표시하고, 대략적/측정 실패 상태를 구분한다 (PROJECT_SCOPE 5절) */
    private fun locationText(loc: EventLogStore.LocationRecord?): String {
        val s = EventLogStore.LocationStatus
        if (loc == null) return getString(R.string.location_off)
        return when (loc.status) {
            s.OFF -> getString(R.string.location_off)
            s.PENDING -> getString(R.string.location_pending)
            s.NO_PERMISSION -> getString(R.string.location_no_permission)
            s.LOCATION_DISABLED -> getString(R.string.location_disabled)
            s.UNAVAILABLE -> getString(R.string.location_unavailable)
            else -> {
                val acc = loc.accuracyMeters?.let { getString(R.string.location_accuracy, it.toInt()) } ?: ""
                val measured = loc.measuredAtMs?.let { timeFormat.format(Date(it)) } ?: "-"
                val key = if (loc.status == s.COARSE) R.string.location_coarse else R.string.location_ok
                getString(key, acc, measured)
            }
        }
    }

    private fun openMap(lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    // ---- 개인정보: 데이터 제공 동의, 위치 기록, 처리방침, 데이터 삭제 ----

    private fun renderPrivacy() {
        val consent = PrivacySettings.canUpload(this)
        binding.dataSharingSwitch.setOnCheckedChangeListener(null)
        binding.dataSharingSwitch.isChecked = consent
        binding.dataSharingSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                PrivacySettings.setDataSharingConsent(this, true)
                UploadQueue.flushAsync(this)
                refresh()
            } else {
                confirmWithdrawConsent()
            }
        }

        val locationOn = PrivacySettings.locationLogEnabled(this) && LocationLogger.hasPermission(this)
        binding.locationSwitch.setOnCheckedChangeListener(null)
        binding.locationSwitch.isChecked = locationOn
        binding.locationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) showLocationDisclosure() else {
                PrivacySettings.setLocationLogEnabled(this, false)
                restartProtectionService()
                refresh()
            }
        }
        binding.privacyPolicyButton.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }
        }
        binding.deleteDataButton.setOnClickListener { confirmDeleteAllData() }
    }

    /** 최초 실행 시 한 번: 서버로 무엇을 보내는지 알리고 선택받는다. 거절해도 감지·알림은 동작 */
    private fun askConsentIfNeeded() {
        if (PrivacySettings.dataSharingConsent(this) != null) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_body)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_agree) { _, _ ->
                PrivacySettings.setDataSharingConsent(this, true)
                refresh()
            }
            .setNegativeButton(R.string.consent_decline) { _, _ ->
                PrivacySettings.setDataSharingConsent(this, false)
                refresh()
            }
            .setNeutralButton(R.string.privacy_policy) { _, _ ->
                // 처리방침을 본 뒤 다시 묻도록 아직 선택하지 않은 상태로 둔다
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }
            }
            .show()
    }

    private fun confirmWithdrawConsent() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.withdraw_title)
            .setMessage(R.string.withdraw_body)
            .setPositiveButton(R.string.withdraw_and_delete) { _, _ ->
                PrivacySettings.setDataSharingConsent(this, false)
                UploadQueue.clearPendingAsync(this)
                deleteServerData(alsoLocal = false)
            }
            .setNegativeButton(R.string.withdraw_keep_server) { _, _ ->
                PrivacySettings.setDataSharingConsent(this, false)
                UploadQueue.clearPendingAsync(this)
                refresh()
            }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun confirmDeleteAllData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_body)
            .setPositiveButton(R.string.delete_confirm) { _, _ -> deleteServerData(alsoLocal = true) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteServerData(alsoLocal: Boolean) {
        val app = applicationContext
        Thread {
            val ok = BackendClient.deleteMyServerData(app)
            if (ok && alsoLocal) {
                EventLogStore.clear(app)
                UploadQueue.clearPendingAsync(app)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, if (ok) R.string.delete_done else R.string.delete_failed, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    /** Play 정책: 위치 권한 요청 전에 무엇을 왜 수집하는지 앱 안에서 먼저 알린다 */
    private fun showLocationDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_disclosure_title)
            .setMessage(R.string.location_disclosure_body)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                locationPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    /** 위치 기록 설정이 바뀌면 Foreground Service를 다시 시작해 location 타입을 붙이거나 뗀다 */
    private fun restartProtectionService() {
        if (ProtectionSettings.isEnabled(this)) MotionForegroundService.start(this)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    /** Android 13+는 알림 런타임 권한이 없으면 의심 태그 알림이 표시되지 않음 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings() // 권한은 있는데 사용자가 설정에서 알림을 끈 경우
        }
    }

    private fun openAppNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun isObserveModeSupported(adapter: NfcAdapter): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && adapter.isObserveModeSupported

    /**
     * 실기기 검증용: 이 기종이 Observe Mode(폴링 프레임 관찰)를 지원하는지.
     * 미지원 기종은 processCommandApdu 경로(데모 AID)만 동작 -> 실제 스키밍 탐지 범위가 제한됨.
     */
    private fun logNfcCapabilities() {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: run {
            Log.w(TAG, "NFC 없음")
            return
        }
        Log.i(TAG, "NFC enabled=${adapter.isEnabled} observeModeSupported=${isObserveModeSupported(adapter)} sdk=${Build.VERSION.SDK_INT}")
    }

    private fun colorList(@ColorRes res: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, res))

    companion object {
        private const val TAG = "NfcGuardMain"
        private const val MAX_HISTORY_ITEMS = 20
    }
}
