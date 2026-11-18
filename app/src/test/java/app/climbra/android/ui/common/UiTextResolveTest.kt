package app.climbra.android.ui.common

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.climbra.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 011: a message's arguments may be a message or a date of their own, resolved in the app's language. */
@RunWith(AndroidJUnit4::class)
class UiTextResolveTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun nestedTextsAndDatesAreResolved() {
        assertEquals(
            "Garmin sync failed: Request failed (502)",
            UiText(R.string.garmin_sync_failed, listOf(UiText(R.string.error_request_failed, listOf(502)))).resolve(resources),
        )
        assertEquals(
            "No new activities since Nov 10, 2026",
            UiText(R.string.garmin_sync_nothing_new, listOf(DateArg("2026-11-10"))).resolve(resources),
        )
        assertEquals(
            "New activities from Garmin: 3",
            UiText(R.plurals.garmin_sync_imported, listOf(3), quantity = 3).resolve(resources),
        )
    }
}
