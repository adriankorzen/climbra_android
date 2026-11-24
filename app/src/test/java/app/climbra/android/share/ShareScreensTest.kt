package app.climbra.android.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.activities.RideDetailScreen
import app.climbra.android.activities.RideDetailUiState
import app.climbra.android.data.api.ActivityAnalysis
import app.climbra.android.data.api.ActivityDetail
import app.climbra.android.data.api.AnalysisResponse
import app.climbra.android.data.api.CalendarRide
import app.climbra.android.data.api.CalendarWeek
import app.climbra.android.data.api.HeartRateMetrics
import app.climbra.android.data.api.PowerMetrics
import app.climbra.android.data.api.Recording
import app.climbra.android.data.api.Samples
import app.climbra.android.data.api.ShareCreated
import app.climbra.android.data.api.ShareKind
import app.climbra.android.data.api.ShareLink
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import app.climbra.android.R
import java.io.File
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 028: the share sheet, the image card, its file and the shared links; texts are English. */
@RunWith(AndroidJUnit4::class)
class ShareScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.ENGLISH)
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun content(body: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { body() } }

    private val samples = Samples(
        t = (0 until 100).map { it * 5 },
        power = List(100) { 200.0 + it },
        heartRate = List(100) { 140.0 },
        speed = List(100) { 8.0 },
    )

    private val analysis = AnalysisResponse(
        activityId = 85,
        analysis = ActivityAnalysis(
            durationSeconds = 2700,
            distanceMeters = 21_600.0,
            power = PowerMetrics(230.0, 287.0, 245.0, 1.07, 0.94, 67.0, 620.0),
            heartRate = HeartRateMetrics(163.0, 175.0),
        ),
    )

    private val activity = ActivityDetail(
        id = 85, name = "Threshold", startTime = "2026-11-08T07:00:00Z", startTimeLocal = "2026-11-08T08:00:00",
        distanceMeters = 21_600.0, elevationGainMeters = 320.0,
    )

    private val rideCard = RideCard.of(activity, analysis, samples)

    private fun rideCard(hidePower: Boolean = false, hideHeartRate: Boolean = false, card: RideCard = rideCard) = content {
        androidx.compose.foundation.layout.Box(Modifier.requiredSize(SHARE_CARD_WIDTH, SHARE_CARD_HEIGHT)) {
            RideShareCard(card, fmt, hidePower, hideHeartRate)
        }
    }

    // AC3: the backend's figures, formatted
    @Test
    fun theRideCardShowsTheFiguresOfTheRide() {
        rideCard()

        compose.onNodeWithTag("card-title").assertTextContains("Threshold")
        compose.onNodeWithTag("card-figure-duration").assertTextContains("45:00", substring = true)
        compose.onNodeWithTag("card-figure-distance").assertTextContains("21.6 km", substring = true)
        compose.onNodeWithTag("card-figure-elevation").assertTextContains("320 m", substring = true)
        compose.onNodeWithTag("card-figure-tss").assertTextContains("67", substring = true)
        compose.onNodeWithTag("card-figure-if").assertTextContains("0.94", substring = true)
        compose.onNodeWithTag("card-figure-np").assertTextContains("245 W", substring = true)
        compose.onNodeWithTag("card-figure-avg-power").assertTextContains("230 W", substring = true)
        compose.onNodeWithTag("card-figure-avg-hr").assertTextContains("163 bpm", substring = true)
        compose.onNodeWithTag("card-chart-power-hr").assertExists()
    }

    // AC3: hidden figures and lines
    @Test
    fun theRideCardLeavesOutWhatIsHidden() {
        rideCard(hidePower = true, hideHeartRate = true)

        compose.onNodeWithTag("card-figure-np").assertDoesNotExist()
        compose.onNodeWithTag("card-figure-avg-power").assertDoesNotExist()
        compose.onNodeWithTag("card-figure-avg-hr").assertDoesNotExist()
        compose.onNodeWithTag("card-figure-tss").assertExists()
        compose.onNodeWithTag("card-figure-if").assertExists()
        // Without power the chart shows the speed
        compose.onNodeWithTag("card-chart-speed").assertExists()
    }

    // AC3: missing values are left out; hrTSS is named
    @Test
    fun theRideCardLeavesOutMissingValues() {
        val heartRateOnly = analysis.copy(analysis = analysis.analysis.copy(power = null, heartRate = HeartRateMetrics(150.0, 170.0, stressScore = 55.0)))
        rideCard(card = RideCard.of(activity.copy(elevationGainMeters = null), heartRateOnly, null))

        compose.onNodeWithTag("card-figure-elevation").assertDoesNotExist()
        compose.onNodeWithTag("card-figure-np").assertDoesNotExist()
        compose.onNodeWithTag("card-figure-if").assertDoesNotExist()
        compose.onNodeWithTag("card-figure-tss").assertTextContains("hrTSS", substring = true).assertTextContains("55", substring = true)
    }

    private fun ride(id: Long, date: String, tss: Double) =
        CalendarRide(activityId = id, date = date, name = "Ride $id", durationSeconds = 3600, trainingLoad = tss)

    // AC4: the week's figures, a bar per day and at most 7 ride lines
    @Test
    fun theWeekCardShowsTheWeeksFiguresAndRides() {
        val rides = (1L..9L).map { ride(it, "2026-11-${15 + it.toInt().coerceAtMost(7)}", 40.0) }
        content {
            androidx.compose.foundation.layout.Box(Modifier.requiredSize(SHARE_CARD_WIDTH, SHARE_CARD_HEIGHT)) {
                WeekShareCard(
                    WeekCard(LocalDate.of(2026, 11, 16), CalendarWeek("2026-11-16", plannedTss = 400, plannedSeconds = 36000, riddenTss = 360, riddenSeconds = 32400), rides),
                    fmt,
                )
            }
        }

        compose.onNodeWithTag("card-title").assertTextContains("Week Nov 16 – Nov 22")
        compose.onNodeWithText("9 rides").assertExists()
        compose.onNodeWithTag("card-figure-tss").assertTextContains("360 / 400", substring = true)
        compose.onNodeWithTag("card-figure-time").assertTextContains("9:00 / 10:00", substring = true)
        compose.onNodeWithTag("card-days").assertExists()
        compose.onAllNodesWithTag("card-ride").fetchSemanticsNodes().let { assertEquals(SHARE_WEEK_RIDE_LINES, it.size) }
        compose.onNodeWithTag("card-more").assertTextContains("+ 2 more")
    }

    private var hidePower = mutableListOf<Boolean>()
    private var links = 0

    private fun sheet(state: ShareUiState, offline: Boolean = false) = content {
        CompositionLocalProvider(LocalOffline provides offline) {
            ShareSheet(state, fmt, ShareActions(onHidePower = { hidePower += it }, onShareLink = { links++ })) { _, _ -> }
        }
    }

    // AC2: no switch for what the ride does not have
    @Test
    fun theSheetOffersOnlyTheSwitchesOfWhatTheRideHas() {
        sheet(ShareUiState(target = ShareTarget.ride(85, "Threshold", hasPower = false, hasHeartRate = true)))

        compose.onNodeWithTag("share-hide-power").assertDoesNotExist()
        compose.onNodeWithTag("share-hide-heart-rate").assertIsDisplayed()
        compose.onNodeWithText("Share ride").assertExists()
    }

    @Test
    fun theSheetSwitchesAndCreatesALinkAndShowsItsExpiry() {
        sheet(
            ShareUiState(
                target = ShareTarget.ride(85, "Threshold", hasPower = true, hasHeartRate = true),
                created = ShareCreated(3, ShareKind.RIDE, "https://climbra.app/s/abc", "2026-12-09T10:00:00Z"),
            ),
        )

        compose.onNodeWithTag("share-hide-power").performClick()
        compose.onNodeWithTag("share-link").performClick()
        assertEquals(listOf(true), hidePower)
        assertEquals(1, links)
        compose.onNodeWithTag("share-expiry").assertTextContains("Dec 9", substring = true)
    }

    // AC6: the translated error; offline the link cannot be created, the image can
    @Test
    fun theSheetShowsTheErrorAndDisablesTheLinkOffline() {
        sheet(ShareUiState(target = ShareTarget.week("2026-11-16", "Week"), error = UiText(R.string.api_error_share_limit_reached)), offline = true)

        compose.onNodeWithTag("share-error").assertTextContains("50 active links", substring = true)
        compose.onNodeWithTag("share-link").assertIsNotEnabled()
        compose.onNodeWithTag("share-image").assertIsEnabled()
        compose.onNodeWithText("Not available offline").assertExists()
        compose.onNodeWithTag("share-hide-power").assertDoesNotExist()
    }

    // AC5: a 1080 × 1350 PNG in cache/shares, shared with read permission
    @Test
    fun theImageIsWrittenToTheCacheAndSharedReadable() {
        val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)

        val file = ShareFiles.savePng(context, bitmap, imageName(ShareTarget.ride(85, "Threshold", true, true)))

        assertEquals(File(File(context.cacheDir, "shares"), "ride-85.png"), file)
        val decoded = BitmapFactory.decodeFile(file.path)
        assertEquals(1080, decoded.width)
        assertEquals(1350, decoded.height)
        val uri = Uri.parse("content://${ShareFiles.authority(context)}/shares/ride-85.png")

        val chooser = ShareFiles.imageIntent(uri, "Share image")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        ShareFiles.clear(context)
        assertTrue(ShareFiles.directory(context).listFiles().isNullOrEmpty())
    }

    /** The provider of the manifest serves cache/shares; its path check needs '/' separators (not Windows). */
    @Test
    fun theProviderServesTheShareImages() {
        assumeTrue(File.separatorChar == '/')
        val file = ShareFiles.savePng(context, Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), "week-2026-11-16")

        val uri = ShareFiles.uri(context, file)

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
    }

    // AC6: the link as text with the title
    @Test
    fun theLinkIsSharedAsTextWithTheTitle() {
        val chooser = ShareFiles.linkIntent("Threshold", "https://climbra.app/s/abc", "Share link")
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!

        assertEquals("text/plain", send.type)
        assertEquals("Threshold – https://climbra.app/s/abc", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Threshold", send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    // AC1: the ride detail offers Share once the ride and its analysis are loaded, and opens the sheet
    @Test
    fun theRideDetailSharesTheLoadedRide() {
        val opened = mutableListOf<ShareTarget>()
        val state = RideDetailUiState(loading = false, recordingLoading = false, activity = activity, analysis = analysis, recording = Recording(5, samples))
        content {
            RideDetailScreen(
                state, fmt, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onShare = { opened += it },
            )
        }

        compose.onNodeWithTag("ride-share").performClick()
        assertEquals(listOf(ShareTarget.ride(85, "Threshold", hasPower = true, hasHeartRate = true)), opened)
    }

    @Test
    fun theRideDetailOffersNoShareBeforeTheAnalysisIsLoaded() {
        content {
            RideDetailScreen(RideDetailUiState(loading = true, activity = activity), fmt, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

        compose.onNodeWithTag("ride-share").assertDoesNotExist()
    }

    private val links3 = listOf(
        ShareLink(4, ShareKind.WEEK, weekStart = "2026-11-16", hideHeartRate = true, expiresAt = "2026-12-09T10:00:00Z"),
        ShareLink(3, ShareKind.RIDE, activityId = 85, activityName = "Threshold", activityDate = "2026-11-08", hidePower = true, expiresAt = "2026-12-08T10:00:00Z"),
    )

    private val revoked = mutableListOf<Long>()
    private var confirmed = 0

    private fun sharedLinks(state: SharedLinksUiState, offline: Boolean = false) = content {
        CompositionLocalProvider(LocalOffline provides offline) {
            SharedLinksScreen(
                state, fmt, onBack = {}, onRetry = {}, onRevoke = { revoked += it.id }, onConfirm = { confirmed++ }, onDismiss = {},
            )
        }
    }

    // AC7: what each link shows and hides, when it expires
    @Test
    fun theSharedLinksListWhatTheyShow() {
        sharedLinks(SharedLinksUiState(loading = false, links = links3))

        compose.onNodeWithTag("shared-link-4").assertTextContains("Week Nov 16 – Nov 22", substring = true)
            .assertTextContains("HR hidden", substring = true).assertTextContains("Expires", substring = true)
        compose.onNodeWithTag("shared-link-3").assertTextContains("Threshold", substring = true)
            .assertTextContains("Power hidden", substring = true)
        compose.onNodeWithTag("revoke-3").performClick()
        assertEquals(listOf(3L), revoked)
    }

    @Test
    fun revokingAsksForAConfirmation() {
        sharedLinks(SharedLinksUiState(loading = false, links = links3, confirming = links3[1]))

        compose.onNodeWithText("Revoke the link?").assertExists()
        compose.onNodeWithTag("confirm-revoke").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun theSharedLinksSayWhenThereAreNone() {
        sharedLinks(SharedLinksUiState(loading = false))

        compose.onNodeWithTag("shared-links-empty").assertTextContains("No active links")
    }

    // AC7: read offline, revoking disabled
    @Test
    fun offlineTheLinksCannotBeRevoked() {
        sharedLinks(SharedLinksUiState(loading = false, links = links3), offline = true)

        compose.onNodeWithTag("revoke-3").assertIsNotEnabled()
        compose.onNodeWithText("Not available offline").assertExists()
    }
}
