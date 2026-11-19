package app.climbra.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Tokens of the web app (cyclingapp frontend/src/styles.scss), mapped to Material 3 roles in spec 001. The light
// background and containers are a step darker than the web so the white, outlined cards stand out on a phone.

internal val LightColors = lightColorScheme(
    primary = Color(0xFFE2531F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFDE7DD),
    onPrimaryContainer = Color(0xFFA93A0B),
    secondaryContainer = Color(0xFFFDE7DD),
    onSecondaryContainer = Color(0xFFA93A0B),
    background = Color(0xFFE9ECF0),
    onBackground = Color(0xFF161B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161B22),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFE4E7EC),
    surfaceContainerHigh = Color(0xFFDCE0E6),
    surfaceContainerHighest = Color(0xFFDCE0E6),
    onSurfaceVariant = Color(0xFF5B6573),
    outlineVariant = Color(0xFFE1E4E9),
    outline = Color(0xFFC9CFD7),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFF26A35),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3A2218),
    onPrimaryContainer = Color(0xFFFF8957),
    secondaryContainer = Color(0xFF3A2218),
    onSecondaryContainer = Color(0xFFFF8957),
    background = Color(0xFF0F1318),
    onBackground = Color(0xFFE7EBF0),
    surface = Color(0xFF171C23),
    onSurface = Color(0xFFE7EBF0),
    surfaceContainerLowest = Color(0xFF0F1318),
    surfaceContainerLow = Color(0xFF171C23),
    surfaceContainer = Color(0xFF1E252E),
    surfaceContainerHigh = Color(0xFF27303B),
    surfaceContainerHighest = Color(0xFF27303B),
    onSurfaceVariant = Color(0xFF9AA5B2),
    outlineVariant = Color(0xFF29313C),
    outline = Color(0xFF3A4552),
    error = Color(0xFFEF5350),
    onError = Color(0xFFFFFFFF),
)

/** Domain colors Material 3 has no role for: signals, zones, chart chrome (same values as the web). */
@Immutable
data class ExtendedColors(
    val textFaint: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val power: Color,
    val heartRate: Color,
    val cadence: Color,
    val speed: Color,
    val altitude: Color,
    /** Coggan power zones Z1-Z7. */
    val powerZones: List<Color>,
    /** Heart rate zones Z1-Z5. */
    val heartRateZones: List<Color>,
    val chartGrid: Color,
    val chartAxis: Color,
)

internal val LightExtendedColors = ExtendedColors(
    textFaint = Color(0xFF6B7480),
    success = Color(0xFF2E7D32),
    warning = Color(0xFFB26A00),
    info = Color(0xFF1D6FD6),
    power = Color(0xFF7C4DFF),
    heartRate = Color(0xFFE0324B),
    cadence = Color(0xFF0F9D76),
    speed = Color(0xFF2F7BF6),
    altitude = Color(0xFFA3ABB5),
    powerZones = listOf(
        Color(0xFF9AA3AD), Color(0xFF2F7BF6), Color(0xFF1FA463), Color(0xFFE3B400),
        Color(0xFFF2771D), Color(0xFFE0324B), Color(0xFF9C4DD6),
    ),
    heartRateZones = listOf(
        Color(0xFF9AA3AD), Color(0xFF2F7BF6), Color(0xFF1FA463), Color(0xFFF2771D), Color(0xFFE0324B),
    ),
    chartGrid = Color(0xFFECEEF2),
    chartAxis = Color(0xFFC9CFD7),
)

internal val DarkExtendedColors = ExtendedColors(
    textFaint = Color(0xFF8A95A3),
    success = Color(0xFF5CBF63),
    warning = Color(0xFFE0A030),
    info = Color(0xFF5A9CF8),
    power = Color(0xFFA283FF),
    heartRate = Color(0xFFFF5C72),
    cadence = Color(0xFF2FC79A),
    speed = Color(0xFF5A9CF8),
    altitude = Color(0xFF6D7885),
    powerZones = listOf(
        Color(0xFF7D8793), Color(0xFF4B8EF8), Color(0xFF34B874), Color(0xFFECC22B),
        Color(0xFFF88A3A), Color(0xFFFF5C72), Color(0xFFB574EA),
    ),
    heartRateZones = listOf(
        Color(0xFF7D8793), Color(0xFF4B8EF8), Color(0xFF34B874), Color(0xFFF88A3A), Color(0xFFFF5C72),
    ),
    chartGrid = Color(0xFF222A33),
    chartAxis = Color(0xFF3A4552),
)
