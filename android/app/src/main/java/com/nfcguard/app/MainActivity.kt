package com.nfcguard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
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

    // 감지 기록/온보딩 baseline이 바뀌면(알림 버튼으로 라벨링 등) 화면 갱신
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.protectionSwitch.isChecked = ProtectionSettings.isEnabled(this)
        binding.protectionSwitch.setOnCheckedChangeListener { _, checked ->
            ProtectionSettings.setEnabled(this, checked)
            if (checked) MotionForegroundService.start(this) else MotionForegroundService.stop(this)
            refresh()
        }

        if (!hasNotificationPermission()) requestNotificationPermission()
        if (ProtectionSettings.isEnabled(this)) MotionForegroundService.start(this)
        logNfcCapabilities()
    }

    override fun onResume() {
        super.onResume()
        EventLogStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        GestureBaselineStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        refresh() // 설정 화면(NFC/배터리/알림)에서 돌아왔을 때 체크리스트 반영
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

        val canLabel = entry.label == null && entry.eventId != null
        item.eventLabelButtons.visibility = if (canLabel) View.VISIBLE else View.GONE
        item.eventLabelState.setText(
            when {
                entry.label == true -> R.string.label_confirmed_mine
                entry.label == false -> R.string.label_confirmed_not_mine
                entry.eventId == null -> R.string.label_pending_upload
                else -> R.string.label_question
            }
        )
        val eventId = entry.eventId ?: return
        item.eventMine.setOnClickListener { submitLabel(item, eventId, wasIntentional = true) }
        item.eventNotMine.setOnClickListener { submitLabel(item, eventId, wasIntentional = false) }
    }

    private fun submitLabel(item: ItemEventBinding, eventId: Long, wasIntentional: Boolean) {
        item.eventMine.isEnabled = false
        item.eventNotMine.isEnabled = false
        LabelActionReceiver.submitAsync(this, eventId, wasIntentional) { ok ->
            if (isFinishing || isDestroyed) return@submitAsync
            if (!ok) {
                item.eventMine.isEnabled = true
                item.eventNotMine.isEnabled = true
                Toast.makeText(this, R.string.label_send_failed, Toast.LENGTH_SHORT).show()
                return@submitAsync
            }
            // 성공 시 EventLogStore 변경 -> prefsListener가 목록을 다시 그림
            if (!wasIntentional) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.not_mine_dialog_title)
                    .setMessage(R.string.not_mine_dialog_body)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }
        }
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
