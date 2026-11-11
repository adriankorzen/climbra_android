package app.climbra.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.climbra.android.R

@OptIn(ExperimentalTextApi::class)
private fun variable(resource: Int, weight: FontWeight) =
    Font(resource, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Text font of the web app (variable Inter). */
val InterFamily = FontFamily(
    variable(R.font.inter, FontWeight.Normal),
    variable(R.font.inter, FontWeight.Medium),
    variable(R.font.inter, FontWeight.SemiBold),
    variable(R.font.inter, FontWeight.Bold),
)

/** Font of numbers and metrics, as `--font-mono` on the web. */
val MonoFamily = FontFamily(
    variable(R.font.jetbrains_mono, FontWeight.Normal),
    variable(R.font.jetbrains_mono, FontWeight.Medium),
)

private val base = Typography()

private fun TextStyle.inter() = copy(fontFamily = InterFamily)

internal val ClimbraTypography = Typography(
    displayLarge = base.displayLarge.inter(),
    displayMedium = base.displayMedium.inter(),
    displaySmall = base.displaySmall.inter(),
    headlineLarge = base.headlineLarge.inter().copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.inter().copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.inter().copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.inter().copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.inter(),
    bodyLarge = base.bodyLarge.inter(),
    bodyMedium = base.bodyMedium.inter(),
    bodySmall = base.bodySmall.inter(),
    labelLarge = base.labelLarge.inter(),
    labelMedium = base.labelMedium.inter(),
    labelSmall = base.labelSmall.inter(),
)
