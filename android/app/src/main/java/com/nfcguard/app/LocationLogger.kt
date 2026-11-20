package com.nfcguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 감지 기록에 위치를 남긴다 (PROJECT_SCOPE 5절). 기기 안에만 저장하고 서버로 보내지 않는다.
 *
 * 원칙:
 * - 이벤트 시점에 새로 측정한다. 마지막으로 알려진 위치를 이벤트 위치로 조용히 대체하지 않는다.
 * - 위치를 못 구하면 그 이유(권한 없음/위치 꺼짐/시간 초과)를 상태로 남긴다.
 * - 대략적 위치만 허용된 경우 COARSE로 구분한다. 측정 시각과 정확도 반경을 함께 저장한다.
 * - 백그라운드에서 측정하려면 MotionForegroundService가 location 타입으로 실행 중이어야 한다.
 */
object LocationLogger {

    private const val TIMEOUT_MS = 15_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun hasPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** NFC 이벤트 직후 호출. 결과는 EventLogStore 해당 항목에 붙는다 (최대 TIMEOUT_MS) */
    fun captureFor(context: Context, localId: String) {
        val app = context.applicationContext
        val record = preflight(app)
        if (record != null) {
            EventLogStore.attachLocation(app, localId, record)
            return
        }
        EventLogStore.attachLocation(app, localId, EventLogStore.LocationRecord(EventLogStore.LocationStatus.PENDING))
        requestCurrentLocation(app) { location ->
            val result = if (location == null) {
                EventLogStore.LocationRecord(EventLogStore.LocationStatus.UNAVAILABLE)
            } else {
                val fine = granted(app, Manifest.permission.ACCESS_FINE_LOCATION)
                EventLogStore.LocationRecord(
                    status = if (fine) EventLogStore.LocationStatus.OK else EventLogStore.LocationStatus.COARSE,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    measuredAtMs = location.time
                )
            }
            EventLogStore.attachLocation(app, localId, result)
        }
    }

    /** 측정할 수 없는 상황이면 그 상태를, 측정 가능하면 null */
    private fun preflight(context: Context): EventLogStore.LocationRecord? {
        if (!PrivacySettings.locationLogEnabled(context)) return EventLogStore.LocationRecord(EventLogStore.LocationStatus.OFF)
        if (!hasPermission(context)) return EventLogStore.LocationRecord(EventLogStore.LocationStatus.NO_PERMISSION)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (pickProvider(lm) == null) return EventLogStore.LocationRecord(EventLogStore.LocationStatus.LOCATION_DISABLED)
        return null
    }

    private fun pickProvider(lm: LocationManager): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER) &&
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)
        ) return LocationManager.FUSED_PROVIDER
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission") // preflight에서 권한 확인
    private fun requestCurrentLocation(context: Context, callback: (Location?) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = pickProvider(lm) ?: return callback(null)
        val done = AtomicBoolean(false)
        val finish: (Location?) -> Unit = { loc -> if (done.compareAndSet(false, true)) executor.execute { callback(loc) } }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                mainHandler.postDelayed({ cancel.cancel(); finish(null) }, TIMEOUT_MS)
                lm.getCurrentLocation(provider, cancel, executor) { finish(it) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        finish(location)
                    }
                    @Deprecated("Deprecated in API 29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                mainHandler.postDelayed({ lm.removeUpdates(listener); finish(null) }, TIMEOUT_MS)
            }
        } catch (e: SecurityException) {
            // 백그라운드에서 location 타입 FGS 없이 호출된 경우 등
            finish(null)
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
