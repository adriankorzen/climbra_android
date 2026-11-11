package app.climbra.android.activities

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.climbra.android.R
import app.climbra.android.data.api.ComplianceLevel
import app.climbra.android.ui.theme.extendedColors
import java.util.Locale

private val SPORTS = mapOf(
    "cycling" to R.string.sport_cycling,
    "road_biking" to R.string.sport_road_biking,
    "road_cycling" to R.string.sport_road_cycling,
    "indoor_cycling" to R.string.sport_indoor_cycling,
    "virtual_ride" to R.string.sport_virtual_ride,
    "gravel_cycling" to R.string.sport_gravel_cycling,
    "mountain_biking" to R.string.sport_mountain_biking,
    "cyclocross" to R.string.sport_cyclocross,
    "track_cycling" to R.string.sport_track_cycling,
    "e_bike_fitness" to R.string.sport_e_bike_fitness,
    "e_bike_mountain" to R.string.sport_e_bike_mountain,
    "running" to R.string.sport_running,
    "walking" to R.string.sport_walking,
    "hiking" to R.string.sport_hiking,
    "strength_training" to R.string.sport_strength_training,
)

private val WORKOUT_TYPES = mapOf(
    "ENDURANCE" to R.string.workout_type_endurance,
    "RECOVERY" to R.string.workout_type_recovery,
    "TEMPO" to R.string.workout_type_tempo,
    "SWEET_SPOT" to R.string.workout_type_sweet_spot,
    "THRESHOLD" to R.string.workout_type_threshold,
    "VO2MAX" to R.string.workout_type_vo2max,
    "ANAEROBIC" to R.string.workout_type_anaerobic,
    "SPRINT" to R.string.workout_type_sprint,
    "MIXED" to R.string.workout_type_mixed,
    "RACE" to R.string.workout_type_race,
    "TEST" to R.string.workout_type_test,
    "REST" to R.string.workout_type_rest,
)

/** "road_biking" → "Road biking", for keys without a translation (the web's `humanize`). */
fun humanize(key: String): String =
    key.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

@Composable
fun sportName(key: String?): String = when {
    key.isNullOrBlank() -> "–"
    else -> SPORTS[key]?.let { stringResource(it) } ?: humanize(key)
}

@Composable
fun workoutTypeName(key: String?): String = when {
    key.isNullOrBlank() -> "–"
    else -> WORKOUT_TYPES[key]?.let { stringResource(it) } ?: humanize(key)
}

@Composable
fun sourceName(key: String?): String? = when (key) {
    "GARMIN" -> stringResource(R.string.source_garmin)
    "UPLOAD" -> stringResource(R.string.source_upload)
    "INTERVALS" -> stringResource(R.string.source_intervals)
    else -> null
}

@Composable
fun complianceName(level: ComplianceLevel): String = stringResource(
    when (level) {
        ComplianceLevel.OK -> R.string.compliance_ok
        ComplianceLevel.MODERATE -> R.string.compliance_moderate
        ComplianceLevel.LARGE -> R.string.compliance_large
    },
)

@Composable
fun complianceColor(level: ComplianceLevel): Color = when (level) {
    ComplianceLevel.OK -> MaterialTheme.extendedColors.success
    ComplianceLevel.MODERATE -> MaterialTheme.extendedColors.warning
    ComplianceLevel.LARGE -> MaterialTheme.colorScheme.error
}

/** Color of an intensity factor, from the power zone it falls into (the web's `intensityColor`). */
@Composable
fun intensityColor(intensity: Double?): Color {
    if (intensity == null) return MaterialTheme.colorScheme.outline
    val zone = when {
        intensity < 0.56 -> 0
        intensity < 0.76 -> 1
        intensity < 0.91 -> 2
        intensity < 1.06 -> 3
        else -> 4
    }
    return MaterialTheme.extendedColors.powerZones[zone]
}
