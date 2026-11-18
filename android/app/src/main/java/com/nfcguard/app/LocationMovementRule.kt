package com.nfcguard.app

/** elapsedMs uses the same monotonic clock as SystemClock.elapsedRealtime(). */
data class MovementFix(
    val elapsedMs: Long,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val horizontalAccuracyM: Double?
)

enum class MovementState { MOVING, STATIONARY, UNKNOWN }

/** Experimental defaults, not measured NFC security thresholds. */
data class MovementRuleConfig(
    val version: Long = 1,
    val movingMinMps: Double = 0.8,
    val stationaryMaxMps: Double = 0.25,
    val maxSpeedAccuracyMps: Double = 0.5,
    val maxHorizontalAccuracyM: Double = 25.0,
    val maxAgeMs: Long = 5_000,
    val maxGapMs: Long = 3_000,
    val minEvidenceMs: Long = 2_000,
    val minSamples: Int = 3
) {
    init {
        require(version > 0)
        require(stationaryMaxMps.isFinite() && stationaryMaxMps >= 0)
        require(movingMinMps.isFinite() && movingMinMps > stationaryMaxMps)
        require(maxSpeedAccuracyMps.isFinite() && maxSpeedAccuracyMps > 0)
        require(maxHorizontalAccuracyM.isFinite() && maxHorizontalAccuracyM > 0)
        require(maxAgeMs > 0 && maxGapMs > 0 && minEvidenceMs > 0 && minSamples >= 2)
    }
}

data class MovementDecision(
    val state: MovementState,
    val reason: String,
    val evaluatedAtMs: Long,
    val ruleVersion: Long,
    val fix: MovementFix?
)

/** O(1) state and work per location update. Does not interpret hand motion as travel. */
class LocationMovementRule(val config: MovementRuleConfig = MovementRuleConfig()) {
    private var lastAcceptedMs: Long? = null
    private var candidate = MovementState.UNKNOWN
    private var candidateStartMs = 0L
    private var count = 0
    private var latest = decision(MovementState.UNKNOWN, "no_location", 0, null)

    @Synchronized
    fun update(fix: MovementFix?, nowMs: Long): MovementDecision {
        val reason = when {
            fix == null -> "no_location"
            fix.elapsedMs < 0 || nowMs < fix.elapsedMs -> "invalid_time"
            nowMs - fix.elapsedMs > config.maxAgeMs -> "stale_location"
            lastAcceptedMs?.let { fix.elapsedMs <= it } == true -> "out_of_order_location"
            !valid(fix.speedMps) || !valid(fix.speedAccuracyMps) || !valid(fix.horizontalAccuracyM) -> "missing_or_invalid_accuracy"
            fix.speedAccuracyMps!! > config.maxSpeedAccuracyMps ||
                fix.horizontalAccuracyM!! > config.maxHorizontalAccuracyM -> "inaccurate_location"
            else -> null
        }
        if (reason != null) {
            resetEvidence()
            latest = decision(MovementState.UNKNOWN, reason, nowMs, fix)
            return latest
        }
        val sample = requireNotNull(fix)
        val speed = requireNotNull(sample.speedMps)
        val error = requireNotNull(sample.speedAccuracyMps)
        // Reported accuracy is an uncertainty estimate, not a guaranteed bound.
        val observed = when {
            speed - error >= config.movingMinMps -> MovementState.MOVING
            speed + error <= config.stationaryMaxMps -> MovementState.STATIONARY
            else -> MovementState.UNKNOWN
        }
        val gap = lastAcceptedMs?.let { sample.elapsedMs - it > config.maxGapMs } ?: true
        lastAcceptedMs = sample.elapsedMs
        if (observed == MovementState.UNKNOWN) {
            resetEvidence()
            latest = decision(MovementState.UNKNOWN, "ambiguous_speed", nowMs, sample)
        } else {
            if (gap || candidate != observed) {
                candidate = observed
                candidateStartMs = sample.elapsedMs
                count = 1
            } else {
                count = (count + 1).coerceAtMost(config.minSamples)
            }
            val confirmed = count >= config.minSamples &&
                sample.elapsedMs - candidateStartMs >= config.minEvidenceMs
            latest = decision(if (confirmed) observed else MovementState.UNKNOWN,
                if (confirmed) "confirmed_${observed.name.lowercase()}" else "collecting_evidence", nowMs, sample)
        }
        return latest
    }

    /** Call at the NFC event time; never reuse an old confirmed state indefinitely. */
    @Synchronized
    fun snapshot(nowMs: Long): MovementDecision {
        val fix = latest.fix
        if (fix == null || nowMs < fix.elapsedMs || nowMs - fix.elapsedMs > config.maxAgeMs) {
            resetEvidence()
            latest = decision(MovementState.UNKNOWN, "stale_or_missing_location", nowMs, fix)
        }
        return latest.copy(evaluatedAtMs = nowMs)
    }

    private fun resetEvidence() { candidate = MovementState.UNKNOWN; count = 0 }
    private fun valid(value: Double?) = value != null && value.isFinite() && value >= 0
    private fun decision(state: MovementState, reason: String, nowMs: Long, fix: MovementFix?) =
        MovementDecision(state, reason, nowMs, config.version, fix)
}
