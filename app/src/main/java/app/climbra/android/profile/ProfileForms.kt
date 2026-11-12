package app.climbra.android.profile

import app.climbra.android.data.api.RiderRequest
import app.climbra.android.data.api.ThresholdRequest
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The rider's fields as typed; rules of `RiderRequest` (spec 004). */
data class RiderForm(
    val name: String = "",
    val weight: String = "",
    val restingHr: String = "",
    /** A save was refused because of the fields. */
    val invalid: Boolean = false,
    val saving: Boolean = false,
) {
    /** The request, `null` when a field breaks the backend's rules. */
    fun request(): RiderRequest? {
        val name = name.trim()
        val weight = decimal(weight) ?: return null
        val restingHr = optionalInt(restingHr) ?: return null
        if (name.isEmpty() || name.length > 100) return null
        if (weight < 20.0 || weight > 250.0 || weight.toBigDecimal().stripTrailingZeros().scale() > 2) return null
        if (restingHr.value != null && restingHr.value !in 25..120) return null
        return RiderRequest(name = name, weightKg = weight, restingHeartRateBpm = restingHr.value)
    }
}

/** A threshold entry as typed; [editingId] is the entry being edited, `null` for a new one. */
data class ThresholdForm(
    val editingId: Long? = null,
    /** ISO day. */
    val effectiveFrom: String,
    val ftp: String = "",
    val lthr: String = "",
    val maxHr: String = "",
    val invalid: Boolean = false,
    val saving: Boolean = false,
) {
    /** The request, `null` when a value is out of the backend's ranges or the day is after [today]. */
    fun request(today: LocalDate): ThresholdRequest? {
        val day = try {
            LocalDate.parse(effectiveFrom)
        } catch (e: DateTimeParseException) {
            return null
        }
        if (day.isAfter(today)) return null
        val ftp = optionalInt(ftp)?.takeIf { it.inRange(30..2500) } ?: return null
        val lthr = optionalInt(lthr)?.takeIf { it.inRange(60..230) } ?: return null
        val maxHr = optionalInt(maxHr)?.takeIf { it.inRange(80..240) } ?: return null
        return ThresholdRequest(effectiveFrom = effectiveFrom, ftpWatts = ftp.value, lthrBpm = lthr.value, maxHeartRateBpm = maxHr.value)
    }
}

/** Connecting intervals.icu; the key is sent once and never kept. */
data class IntervalsForm(val athleteId: String = "", val apiKey: String = "", val busy: Boolean = false)

data class PasswordForm(
    val current: String = "",
    val password: String = "",
    val repeat: String = "",
    /** Show the rule a field breaks: after a submit. */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
)

/** What is wrong with a new password; the rules of the backend and the web (cyclingapp spec 006). */
enum class PasswordProblem { REQUIRED, TOO_SHORT, TOO_LONG, MISMATCH, EQUALS_EMAIL }

/** Length counts code points, the upper limit UTF-8 bytes (bcrypt uses 72), as the backend counts them. */
fun newPasswordProblem(password: String): PasswordProblem? = when {
    password.isEmpty() -> PasswordProblem.REQUIRED
    password.codePointCount(0, password.length) < PASSWORD_MIN_LENGTH -> PasswordProblem.TOO_SHORT
    password.toByteArray(Charsets.UTF_8).size > PASSWORD_MAX_BYTES -> PasswordProblem.TOO_LONG
    else -> null
}

fun repeatProblem(password: String, repeat: String, email: String): PasswordProblem? = when {
    repeat.isEmpty() -> PasswordProblem.REQUIRED
    password != repeat -> PasswordProblem.MISMATCH
    password.trim().equals(email.trim(), ignoreCase = true) -> PasswordProblem.EQUALS_EMAIL
    else -> null
}

const val PASSWORD_MIN_LENGTH = 10
const val PASSWORD_MAX_BYTES = 72

/** A field that may stay empty: [value] is `null` then. */
@JvmInline
value class OptionalInt(val value: Int?) {
    fun inRange(range: IntRange) = value == null || value in range
}

/** `null` when the text is not a whole number; an empty text is an empty value. */
internal fun optionalInt(text: String): OptionalInt? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return OptionalInt(null)
    return trimmed.toIntOrNull()?.let { OptionalInt(it) }
}

/** A decimal typed with a comma or a dot, as the keyboard of either language gives it. */
internal fun decimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** A decimal for a text field, without a needless `.0`. */
internal fun decimalText(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> value.toBigDecimal().stripTrailingZeros().toPlainString()
}
