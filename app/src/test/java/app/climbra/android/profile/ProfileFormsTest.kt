package app.climbra.android.profile

import app.climbra.android.data.api.RiderRequest
import app.climbra.android.data.api.ThresholdEntry
import app.climbra.android.data.api.ThresholdRequest
import app.climbra.android.data.api.ThresholdValues
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 004: the form rules of the backend, and the FTP trend's steps from the backend's resolved values. */
class ProfileFormsTest {

    private val today = LocalDate.of(2026, 11, 12)

    // AC2
    @Test
    fun theRiderFormFollowsTheBackendsRules() {
        assertEquals(RiderRequest("Anna", 61.5, 48), RiderForm(" Anna ", "61,5", "48").request())
        assertEquals(RiderRequest("Anna", 20.0, null), RiderForm("Anna", "20", "").request())
        assertNull(RiderForm("", "60").request())
        assertNull(RiderForm("A".repeat(101), "60").request())
        assertNull(RiderForm("Anna", "250.01").request())
        assertNull(RiderForm("Anna", "60.125").request())
        assertNull(RiderForm("Anna", "sixty").request())
        assertNull(RiderForm("Anna", "60", "24").request())
    }

    // AC5
    @Test
    fun theThresholdFormFollowsTheBackendsRanges() {
        val form = ThresholdForm(effectiveFrom = "2026-11-12", ftp = "250", lthr = "", maxHr = "185")
        assertEquals(ThresholdRequest("2026-11-12", 250, null, 185), form.request(today))
        assertEquals(ThresholdRequest("2026-11-12", 30, 230, 80), form.copy(ftp = "30", lthr = "230", maxHr = "80").request(today))
        assertNull(form.copy(ftp = "2501").request(today))
        assertNull(form.copy(lthr = "59").request(today))
        assertNull(form.copy(maxHr = "79").request(today))
        assertNull(form.copy(effectiveFrom = "2026-11-13").request(today))
        assertNull(form.copy(effectiveFrom = "").request(today))
    }

    // AC13
    @Test
    fun passwordsAreCheckedLikeTheBackend() {
        assertEquals(PasswordProblem.REQUIRED, newPasswordProblem(""))
        assertEquals(PasswordProblem.TOO_SHORT, newPasswordProblem("123456789"))
        assertNull(newPasswordProblem("1234567890"))
        // Code points, not UTF-16 units: ten emoji are ten characters
        assertNull(newPasswordProblem("😀".repeat(10)))
        // 72 UTF-8 bytes at most: 36 two-byte letters fit, 37 do not
        assertNull(newPasswordProblem("ż".repeat(36)))
        assertEquals(PasswordProblem.TOO_LONG, newPasswordProblem("ż".repeat(37)))
        assertEquals(PasswordProblem.MISMATCH, repeatProblem("battery-staple", "battery-stapler", "a@b.c"))
        assertEquals(PasswordProblem.EQUALS_EMAIL, repeatProblem(" Anna@Example.com", " Anna@Example.com", "anna@example.com"))
        assertNull(repeatProblem("battery-staple", "battery-staple", "anna@example.com"))
    }

    // AC6
    @Test
    fun theTrendStepsAreTheResolvedFtpHeldUntilToday() {
        val entries = listOf(
            ThresholdEntry(id = 3, effectiveFrom = "2026-10-20", ftpWatts = null, resolved = ThresholdValues(ftpWatts = 270)),
            ThresholdEntry(id = 2, effectiveFrom = "2026-09-01", ftpWatts = 250, resolved = ThresholdValues(ftpWatts = 250)),
            ThresholdEntry(id = 1, effectiveFrom = "2026-08-01", ftpWatts = null, resolved = ThresholdValues()),
        )

        assertEquals(
            listOf(
                FtpStep(LocalDate.of(2026, 9, 1), 250),
                FtpStep(LocalDate.of(2026, 10, 20), 270),
                FtpStep(today, 270),
            ),
            ftpSteps(entries, today),
        )
        assertEquals(emptyList<FtpStep>(), ftpSteps(listOf(entries.last()), today))
        // An entry of today is not repeated
        assertEquals(listOf(FtpStep(today, 300)), ftpSteps(listOf(ThresholdEntry(effectiveFrom = "2026-11-12", resolved = ThresholdValues(300))), today))
    }
}
