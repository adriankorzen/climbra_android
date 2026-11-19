package app.climbra.android.dashboard

import app.climbra.android.ui.format.Fmt
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardChartsTest {

    private val polish = Fmt(Locale.forLanguageTag("pl"))
    private val english = Fmt(Locale.forLanguageTag("en"))

    @Test
    fun `the value axes are labelled in the locale, whole numbers without decimals`() {
        assertEquals("1200", axisNumber(english, 1200.0).replace(",", ""))
        assertEquals("-20", axisNumber(english, -20.0))
        assertEquals("0", axisNumber(polish, 0.0))
        // The hours axis: a fraction keeps one decimal, with the locale's separator
        assertEquals("1,5", axisNumber(polish, 1.5))
        assertEquals("1.3", axisNumber(english, 1.33))
    }
}
