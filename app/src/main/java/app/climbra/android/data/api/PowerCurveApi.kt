package app.climbra.android.data.api

import kotlinx.serialization.Serializable

// Mirror `analysis.PowerCurveResponse` of the backend (cyclingapp spec 061) and `PowerCurveResponse` of the web

/** The power curve of a period ending today and of the period of the same length before it. */
@Serializable
data class PowerCurveResponse(
    /** `null` for all rides. */
    val days: Int? = null,
    val current: PowerCurvePeriod = PowerCurvePeriod(),
    /** `null` for all rides. */
    val previous: PowerCurvePeriod? = null,
    /** Rides analysed before the curve existed, left out until they are recalculated. */
    val ridesWithoutCurve: Int = 0,
)

@Serializable
data class PowerCurvePeriod(
    /** `null` for all rides. */
    val from: String? = null,
    val to: String? = null,
    /** The durations some ride of the period reaches, shortest first. */
    val points: List<PowerCurvePoint> = emptyList(),
)

/** The best power of a duration, with the ride it comes from. */
@Serializable
data class PowerCurvePoint(
    val durationSeconds: Int,
    val watts: Int,
    /** With the rider's current weight. */
    val wattsPerKg: Double? = null,
    val activityId: Long,
    /** Local date of the ride. */
    val date: String,
    /** Against the previous period's best of the duration, only in the current period. */
    val changeWatts: Int? = null,
)
