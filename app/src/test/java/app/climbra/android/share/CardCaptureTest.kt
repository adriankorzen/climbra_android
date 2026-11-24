package app.climbra.android.share

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.data.api.ActivityAnalysis
import app.climbra.android.data.api.ActivityDetail
import app.climbra.android.data.api.AnalysisResponse
import app.climbra.android.data.api.PowerMetrics
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Spec 028 AC5: the card is drawn off screen into a 1080 × 1350 bitmap with real drawing (native graphics). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theCardIsDrawnAtTheImageSizeInTheLightTheme() {
        val card = RideCard.of(
            ActivityDetail(id = 85, name = "Threshold", startTime = "2026-11-08T07:00:00Z"),
            AnalysisResponse(85, analysis = ActivityAnalysis(durationSeconds = 2700, power = PowerMetrics(230.0, 287.0, 245.0))),
            null,
        )
        var captured: Bitmap? = null
        var calls = 0
        compose.setContent {
            // The dark theme around it: the card still has the light theme's surface
            ClimbraTheme(darkTheme = true) {
                CardCapture(onCaptured = { captured = it; calls++ }) {
                    RideShareCard(card, Fmt(Locale.ENGLISH), hidePower = false, hideHeartRate = false)
                }
            }
        }
        compose.waitUntil(5_000) { calls > 0 }

        assertNotNull(captured)
        val bitmap = captured!!
        assertEquals(1080, bitmap.width)
        assertEquals(1350, bitmap.height)
        // A corner of the card is the light surface, not transparent and not dark
        val corner = bitmap.getPixel(1079, 1349)
        assertEquals(0xFF, corner ushr 24)
        assertEquals(true, android.graphics.Color.luminance(corner) > 0.8f)
        assertEquals(1, calls)
    }

    // Share image in the sheet draws the card and hands the bitmap over, ready again afterwards
    @Test
    fun shareImageInTheSheetHandsOverTheCard() {
        val card = RideCard.of(ActivityDetail(id = 85, name = "Threshold", startTime = "2026-11-08T07:00:00Z"), null, null)
        val images = mutableListOf<Bitmap?>()
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                ShareSheet(ShareUiState(target = ShareTarget.ride(85, "Threshold", true, true)), Fmt(Locale.ENGLISH), ShareActions(onImage = { images += it })) { hidePower, hideHeartRate ->
                    RideShareCard(card, Fmt(Locale.ENGLISH), hidePower, hideHeartRate)
                }
            }
        }

        compose.onNodeWithTag("share-image").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            images.isNotEmpty()
        }

        val bitmap = images.single()
        assertNotNull(bitmap)
        assertEquals(1080, bitmap!!.width)
        assertEquals(1350, bitmap.height)
        compose.onNodeWithTag("share-image").assertIsEnabled()
    }
}
