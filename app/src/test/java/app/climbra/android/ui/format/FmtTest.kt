package app.climbra.android.ui.format

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 002 AC10: numbers, percent, durations and dates in the app's language (Robolectric for the date patterns). */
@RunWith(AndroidJUnit4::class)
class FmtTest {

    private val polish = Fmt(Locale.forLanguageTag("pl"))
    private val english = Fmt(Locale.forLanguageTag("en"))

    /** Group separators are no-break spaces in Polish; compare with plain ones. */
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun numbersFollowTheLanguage() {
        assertEquals("1 234,5", polish.num(1234.5, 1).plain())
        assertEquals("1,234.5", english.num(1234.5, 1))
        assertEquals("333", polish.num(332.8))
        assertEquals("0,84", polish.num(0.836, 2))
        // An exact half rounds away from zero, as Intl.NumberFormat
        assertEquals("0,13", polish.num(0.125, 2))
        assertEquals("3", polish.num(2.5))
        assertEquals("151", polish.km(151_200.0, 0))
        assertEquals("75.5", english.km(75_500.0))
    }

    @Test
    fun missingValuesAreADash() {
        assertEquals("–", polish.num(null))
        assertEquals("–", polish.num(Double.NaN))
        assertEquals("–", polish.km(null))
        assertEquals("–", polish.duration(null))
        assertEquals("–", polish.hm(null))
        assertEquals("–", polish.date(null, DateStyle.LONG))
        assertEquals("–", polish.date("soon", DateStyle.LONG))
    }

    @Test
    fun aValueThatRoundsToZeroHasNoSign() {
        assertEquals("0", polish.num(-0.4))
        assertEquals("0", polish.signed(0.3))
        assertEquals("+12", polish.signed(12.2))
        assertEquals("-7", polish.signed(-7.2).replace('−', '-'))
    }

    @Test
    fun changesShowTheDirectionAndTheAbsolutePercent() {
        assertEquals("▲ 50%", polish.change(0.5))
        assertEquals("▼ 12%", polish.change(-0.123))
        assertEquals("• 0%", polish.change(0.0))
        assertEquals("▲ 50%", english.change(0.5))
        assertNull(polish.change(null))
        assertNull(polish.change(Double.NaN))
    }

    @Test
    fun durationsAreClockLike() {
        assertEquals("1:00:00", polish.duration(3600))
        assertEquals("45:00", polish.duration(2700))
        assertEquals("2:03:09", polish.duration(7389.4))
        assertEquals("5:15", polish.hm(18_900))
        assertEquals("0:01", polish.hm(40))
    }

    @Test
    fun datesFollowTheLanguageAndKeepTheLocalDay() {
        assertEquals("środa, 11 listopada 2026", polish.date("2026-11-11", DateStyle.LONG))
        assertEquals("Wednesday, November 11, 2026", english.date("2026-11-11", DateStyle.LONG))
        assertEquals("śr., 11 lis", polish.date("2026-11-11T23:30:00", DateStyle.WEEKDAY))
        assertEquals("Wed, Nov 11", english.date("2026-11-11T23:30:00", DateStyle.WEEKDAY))
        assertEquals("11 lis", polish.date("2026-11-11", DateStyle.DAY_MONTH))
        assertEquals("Nov 11", english.date("2026-11-11", DateStyle.DAY_MONTH))
    }

    // Spec 004: instants (last sync, credit renewal) in the device's zone
    @Test
    fun instantsAreShownInTheDevicesZone() {
        val warsaw = java.time.ZoneId.of("Europe/Warsaw")
        assertEquals("26 paź 2026, 10:00", polish.instant("2026-10-26T09:00:00Z", zone = warsaw).plain())
        assertEquals("1 gru 2026", polish.instant("2026-11-30T23:30:00Z", withTime = false, zone = warsaw))
        assertEquals("–", polish.instant(null))
        assertEquals("–", polish.instant("yesterday"))
    }

    // Spec 006: "3 min ago" of the conversation list, as the web's formatRelative
    @Test
    fun relativeTimesFollowTheWeb() {
        val now = java.time.Instant.parse("2026-11-12T10:00:00Z")
        val utc = java.time.ZoneOffset.UTC
        assertEquals("now", english.relative("2026-11-12T10:00:00Z", now, utc))
        assertEquals("3 minutes ago", english.relative("2026-11-12T09:57:00Z", now, utc))
        assertEquals("2 hours ago", english.relative("2026-11-12T08:00:00Z", now, utc))
        assertEquals("yesterday", english.relative("2026-11-11T09:00:00Z", now, utc))
        assertEquals("wczoraj", polish.relative("2026-11-11T09:00:00Z", now, utc))
        assertEquals("3 minuty temu", polish.relative("2026-11-12T09:57:00Z", now, utc))
        // After 30 days the date
        assertEquals("1 paź 2026", polish.relative("2026-10-01T10:00:00Z", now, utc))
        assertEquals("–", english.relative(null, now))
    }

    // Spec 006: a message's time, with its day when not today
    @Test
    fun messageTimesAreTheTimeOfTodayElseWithTheDay() {
        val now = java.time.Instant.parse("2026-11-12T10:00:00Z")
        val utc = java.time.ZoneOffset.UTC
        assertEquals("09:05", polish.timeOrDate("2026-11-12T09:05:00Z", now, utc))
        assertEquals("11 lis 2026, 09:05", polish.timeOrDate("2026-11-11T09:05:00Z", now, utc).plain())
    }
}
