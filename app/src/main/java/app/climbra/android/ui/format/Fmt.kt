package app.climbra.android.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Formats of [Fmt.date]; the names of the web app's `DateStyle`. */
enum class DateStyle(internal val skeleton: String) {
    /** Sunday, 25 October 2026 */
    LONG("EEEE, d MMMM y"),

    /** Sun, 25 Oct */
    WEEKDAY("EEE, d MMM"),

    /** 25 Oct 2026 */
    DAY_MONTH_YEAR("d MMM y"),

    /** 25 Oct */
    DAY_MONTH("d MMM"),
}

/**
 * Numbers, durations and dates in the app's language; the Android counterpart of `shared/fmt.ts` and
 * `shared/format.ts` of the web app. Missing values are a dash.
 */
class Fmt(val locale: Locale) {

    fun num(value: Number?, digits: Int = 0): String {
        val number = value?.toDouble()
        if (number == null || number.isNaN() || number.isInfinite()) return DASH
        val format = NumberFormat.getNumberInstance(locale).apply {
            // As Intl.NumberFormat of the web app: halves away from zero
            roundingMode = RoundingMode.HALF_UP
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        val text = format.format(number)
        // A value that rounds to zero has no sign: -0.4 is "0", not "-0"
        return if (text.none { it in '1'..'9' }) format.format(0) else text
    }

    /** A number with its sign, e.g. the form (TSB): `+12`, `-7`, `0`. */
    fun signed(value: Number?, digits: Int = 0): String {
        val text = num(value, digits)
        return if (value != null && value.toDouble() > 0 && text.any { it in '1'..'9' }) "+$text" else text
    }

    /** Distance in km from meters. */
    fun km(meters: Number?, digits: Int = 1): String = num(meters?.toDouble()?.div(1000), digits)

    /** A fraction as percent without sign handling: `0.5` is `50%`. */
    fun pct(fraction: Number?, digits: Int = 0): String {
        val number = fraction?.toDouble()
        if (number == null || number.isNaN() || number.isInfinite()) return DASH
        return NumberFormat.getPercentInstance(locale).apply {
            roundingMode = RoundingMode.HALF_UP
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(number).replace(" ", "")
    }

    /** A relative change like the web's KPI tiles: `▲ 50%`, `▼ 12%`, `• 0%`; `null` when there is none to show. */
    fun change(fraction: Double?): String? {
        if (fraction == null || fraction.isNaN() || fraction.isInfinite()) return null
        val arrow = if (fraction > 0) "▲" else if (fraction < 0) "▼" else "•"
        return "$arrow ${pct(abs(fraction))}"
    }

    /** `h:mm:ss`, or `m:ss` under an hour. */
    fun duration(totalSeconds: Number?): String {
        val value = totalSeconds?.toDouble()
        if (value == null || value.isNaN()) return DASH
        val seconds = value.roundToLong()
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** `h:mm`, for totals where seconds do not matter. */
    fun hm(totalSeconds: Number?): String {
        val value = totalSeconds?.toDouble()
        if (value == null || value.isNaN()) return DASH
        val minutes = (value / 60).roundToLong()
        return "%d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
    }

    /** [value] is an ISO date or local date-time (`2026-10-25`, `2026-10-25T08:00:00`); the day part is shown. */
    fun date(value: String?, style: DateStyle): String {
        if (value == null || value.length < ISO_DATE_LENGTH) return DASH
        val date = try {
            LocalDate.parse(value.substring(0, ISO_DATE_LENGTH))
        } catch (e: DateTimeParseException) {
            return DASH
        }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, style.skeleton)
        return DateTimeFormatter.ofPattern(pattern, locale).format(date)
    }

    /**
     * [value] is an ISO instant (`2026-10-26T09:00:00Z`), shown in the device's [zone]: `26 Oct 2026, 10:00`, or the
     * day alone without [withTime] — the web's `fmt.dateTime` and `fmt.date(…, 'medium')` of an instant.
     */
    fun instant(value: String?, withTime: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): String {
        val instant = try {
            Instant.parse(value ?: return DASH)
        } catch (e: DateTimeParseException) {
            return DASH
        }
        val skeleton = if (withTime) "d MMM y jj:mm" else DateStyle.DAY_MONTH_YEAR.skeleton
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone))
    }

    companion object {
        const val DASH = "–"
        private const val ISO_DATE_LENGTH = 10
    }
}

/** [Fmt] in the app's current language (it follows the account, spec 001). */
@Composable
fun rememberFmt(): Fmt {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { Fmt(locale) }
}
