package com.nfcguard.app

import android.content.Context

object GestureBaselineStore {
    private const val PREFS = "nfcguard_baseline"

    fun save(context: Context, baseline: UserGestureBaseline) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("disp", baseline.typicalNetDisplacement.toFloat())
            .putFloat("orient", baseline.typicalOrientationShift.toFloat())
            .putInt("count", baseline.sampleCount)
            .apply()
    }

    fun load(context: Context): UserGestureBaseline? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("disp")) return null
        return UserGestureBaseline(
            typicalNetDisplacement = prefs.getFloat("disp", 0f).toDouble(),
            typicalOrientationShift = prefs.getFloat("orient", 0f).toDouble(),
            sampleCount = prefs.getInt("count", 0)
        )
    }
}
