package com.nfcguard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat

/** Feed from an authorized location callback. This adapter does not start tracking. */
fun Location.toMovementFix() = MovementFix(
    elapsedMs = elapsedRealtimeNanos / 1_000_000L,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
    horizontalAccuracyM = if (hasAccuracy()) accuracy.toDouble() else null
)

/** The service owns collection. HCE only reads a snapshot on the main thread. */
class AndroidMovementInput(private val context: Context) : LocationListenerCompat {
    data class Snapshot(val state: MovementState, val location: Location?)

    private val manager = context.getSystemService(LocationManager::class.java)
    private val rule = LocationMovementRule()
    private var lastLocation: Location? = null
    private var collecting = false

    fun start() {
        if (collecting || !hasFineLocation(context)) return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            collecting = true
        } catch (_: SecurityException) {
            clear()
        } catch (_: IllegalArgumentException) {
            clear() // GPS provider unavailable
        }
    }

    fun stop() {
        if (collecting) manager.removeUpdates(this)
        collecting = false
        clear()
    }

    override fun onLocationChanged(location: Location) {
        if (!collecting) return
        val fix = location.toMovementFix()
        val now = SystemClock.elapsedRealtime()
        if (lastLocation?.let { location.elapsedRealtimeNanos <= it.elapsedRealtimeNanos } == true) return
        if (fix.elapsedMs !in (now - rule.config.maxAgeMs)..now) return
        lastLocation = Location(location)
        rule.update(fix, now)
    }

    override fun onProviderDisabled(provider: String) = clear()

    fun snapshot(atNanos: Long): Snapshot {
        if (!collecting || !hasFineLocation(context)) return Snapshot(MovementState.UNKNOWN, null)
        val atMs = atNanos / 1_000_000L
        val decision = rule.snapshot(atMs)
        val freshLocation = lastLocation?.takeIf { location ->
            val ageMs = atMs - location.elapsedRealtimeNanos / 1_000_000L
            location.elapsedRealtimeNanos <= atNanos && ageMs in 0..rule.config.maxAgeMs && location.hasAccuracy()
        }
        return Snapshot(decision.state, freshLocation)
    }

    private fun clear() {
        lastLocation = null
        rule.update(null, SystemClock.elapsedRealtime())
    }

    companion object {
        fun hasFineLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
