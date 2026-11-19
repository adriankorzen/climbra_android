package app.climbra.android.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.data.api.AccountResponse
import app.climbra.android.data.api.BonusCredits
import app.climbra.android.data.api.CoachQuota
import app.climbra.android.data.api.GarminConnection
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.IntervalsConnection
import app.climbra.android.data.api.IntervalsThresholds
import app.climbra.android.data.api.MonthlyCredits
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.ThresholdEntry
import app.climbra.android.data.api.ThresholdValues
import app.climbra.android.data.api.TrainingZone
import app.climbra.android.data.api.Zones
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import app.climbra.android.ui.theme.ThemeMode
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 004: the profile screen shows the state as it is; texts are English (the default resources). */
@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val polish = Fmt(Locale.forLanguageTag("pl"))
    private val today = LocalDate.of(2026, 11, 12)

    private val october = ThresholdEntry(
        id = 11, effectiveFrom = "2026-10-20", ftpWatts = 270, ftpWattsPerKg = 4.5,
        resolved = ThresholdValues(270, 165, 185),
    )
    private val september = ThresholdEntry(
        id = 10, effectiveFrom = "2026-09-01", ftpWatts = 250, lthrBpm = 165, maxHeartRateBpm = 185, ftpWattsPerKg = 4.17,
        resolved = ThresholdValues(250, 165, 185),
    )
    private val rider = Rider(
        id = 5, name = "Anna", weightKg = 60.0, restingHeartRateBpm = 48,
        currentThresholds = october.copy(lthrBpm = 165, maxHeartRateBpm = 185), ftpWattsPerKg = 4.5,
    )
    private val zones = Zones(
        ftpWatts = 270, lthrBpm = 165, maxHeartRateBpm = 185,
        powerZones = listOf(
            TrainingZone(4, "Lactate Threshold", 91, 105, 246, 284),
            TrainingZone(7, "Neuromuscular Power", 151, null, 408, null),
        ),
        heartRateZoneBasis = "LTHR",
        heartRateZones = listOf(TrainingZone(3, "Tempo", 84, 94, 139, 155)),
    )
    private val account = AccountResponse(
        id = 7, email = "anna@example.com", role = "USER", language = "en", hasPassword = true, googleLinked = true,
        termsAccepted = true,
    )
    private val quota = CoachQuota(
        plan = "FREE", limit = 30, used = 5, remaining = 40,
        monthly = MonthlyCredits(30, 5, 25, "2026-12-01T12:00:00Z"),
        bonus = listOf(BonusCredits(15, "2027-01-31T12:00:00Z")),
    )
    private val connected = IntervalsConnection(
        connected = true, athleteId = "i12345", athleteName = "Anna Nowak", keyHint = "x7Qa",
        lastSyncAt = "2026-10-26T09:00:00Z",
    )

    private val ready = ProfileUiState(
        loading = false,
        rider = rider,
        account = account,
        thresholds = Section(value = listOf(october, september)),
        zones = Section(value = zones),
        intervals = Section(value = IntervalsConnection()),
        garmin = Section(value = GarminConnection(available = true, status = GarminStatus.CONNECTED, connectedAt = "2026-10-01T08:00:00Z", sent = 4, stale = 1)),
        quota = Section(value = quota),
        riderForm = RiderForm(name = "Anna", weight = "60", restingHr = "48"),
    )

    /** Records what the screen asks for. */
    private class Recorder : ProfileActions {
        val calls = mutableListOf<String>()
        override fun retry(section: ProfileSection) { calls += "retry:$section" }
        override fun saveRider() { calls += "saveRider" }
        override fun openThresholdForm(entry: ThresholdEntry?) { calls += "openThresholdForm:${entry?.id}" }
        override fun saveThreshold() { calls += "saveThreshold" }
        override fun askDeleteThreshold(entry: ThresholdEntry) { calls += "askDelete:${entry.id}" }
        override fun confirm() { calls += "confirm" }
        override fun connectIntervals() { calls += "connectIntervals" }
        override fun syncIntervals() { calls += "syncIntervals" }
        override fun askDisconnectIntervals() { calls += "askDisconnectIntervals" }
        override fun proposeThresholds(proposal: IntervalsThresholds) { calls += "propose:${proposal.ftpWatts}" }
        override fun askDisconnectGarmin() { calls += "askDisconnectGarmin" }
        override fun connectGarmin() { calls += "connectGarmin" }
        override fun setLanguage(language: String) { calls += "language:$language" }
        override fun setTheme(mode: ThemeMode) { calls += "theme:$mode" }
        override fun changePassword() { calls += "changePassword" }
        override fun forgotPassword() { calls += "forgotPassword" }
        override fun openDeleteAccount() { calls += "openDeleteAccount" }
        override fun deleteAccount() { calls += "deleteAccount" }
        override fun logout() { calls += "logout" }
        override fun openFeedback() { calls += "openFeedback" }
        override fun syncGarmin() { calls += "syncGarmin" }
    }

    private val actions = Recorder()

    private fun show(state: ProfileUiState) = compose.setContent {
        ClimbraTheme(darkTheme = false) { ProfileScreen(state, polish, today, actions) }
    }

    /** Buttons and clickable rows merge their texts; [unmerged] finds a tagged node inside one. */
    private fun tag(tag: String, unmerged: Boolean = false): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged)

    private fun scrolled(tag: String, unmerged: Boolean = false) = tag(tag, unmerged).performScrollTo()

    /** A text somewhere inside the container tagged [tag]. */
    private fun contains(tag: String, text: String) {
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasText(text, substring = true), useUnmergedTree = true)
            .onFirst().assertExists()
    }

    // AC1
    @Test
    fun theHeaderShowsTheCurrentThresholds() {
        show(ready)

        tag("profile-title").assertTextContains("Profile")
        tag("profile-subtitle").assertTextContains("FTP 270 W (4,50 W/kg) · LTHR 165 bpm · Max HR 185 bpm")
    }

    // AC1
    @Test
    fun aFailingSectionShowsItsErrorWhileTheOthersShowTheirData() {
        show(ready.copy(zones = Section(failed = true)))

        scrolled("power-zones-retry").performClick()
        assertEquals(listOf("retry:ZONES"), actions.calls)
        scrolled("threshold-11").assertIsDisplayed()
        scrolled("quota").assertIsDisplayed()
    }

    // AC1
    @Test
    fun aRiderThatCannotBeLoadedShowsTheErrorWithRetry() {
        show(ProfileUiState(loading = false, riderFailed = true, account = account))

        tag("profile-error").assertIsDisplayed()
        tag("profile-rider").assertDoesNotExist()
        tag("profile-thresholds").assertDoesNotExist()
        scrolled("profile-account").assertIsDisplayed()
    }

    // AC2
    @Test
    fun theRiderFormShowsItsFieldsAndRefusesInvalidOnes() {
        show(ready.copy(riderForm = ready.riderForm.copy(invalid = true)))

        tag("rider-name").assertTextContains("Anna")
        tag("rider-weight").assertTextContains("60")
        tag("rider-invalid").assertIsDisplayed()
        tag("rider-save").performClick()
        assertEquals(listOf("saveRider"), actions.calls)
    }

    // AC3
    @Test
    fun anAccountWithoutRiderIsAskedToCreateIt() {
        show(ProfileUiState(loading = false, noRider = true, account = account, quota = Section(value = quota)))

        tag("profile-title").assertTextContains("Create your profile")
        tag("rider-save").assertTextContains("Create profile")
        tag("profile-thresholds").assertDoesNotExist()
        tag("profile-intervals").assertDoesNotExist()
        scrolled("profile-account").assertIsDisplayed()
    }

    // AC4
    @Test
    fun theHistoryShowsTheBackendsWattsPerKgAndTheCurrentEntry() {
        show(ready)

        scrolled("threshold-11-values", unmerged = true).assertTextContains("FTP 270 W · 4,50 W/kg · LTHR – · Max HR –")
        scrolled("threshold-10-values", unmerged = true).assertTextContains("FTP 250 W · 4,17 W/kg · LTHR 165 bpm · Max HR 185 bpm")
        // Newest first; only the entry valid today is current
        val rows = compose.onAllNodesWithTag("threshold-current", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, rows.size)
        tag("threshold-11").assertTextContains("current")
        assertTrue(
            compose.onNodeWithTag("threshold-11").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithTag("threshold-10").fetchSemanticsNode().boundsInRoot.top,
        )
    }

    // AC4
    @Test
    fun anEmptyHistorySaysSo() {
        show(ready.copy(thresholds = Section(value = emptyList())))

        scrolled("thresholds-empty").assertIsDisplayed()
        scrolled("ftp-trend-empty").assertIsDisplayed()
    }

    // AC5
    @Test
    fun entriesAreAddedEditedAndDeletedFromTheList() {
        show(ready)

        scrolled("threshold-add").performClick()
        tag("threshold-10").performClick()
        tag("threshold-10-menu").performClick()
        compose.onNodeWithTag("threshold-delete").performClick()

        assertEquals(listOf("openThresholdForm:null", "openThresholdForm:10", "askDelete:10"), actions.calls)
    }

    // AC5
    @Test
    fun deletingAnEntryAsksFirst() {
        show(ready.copy(confirmation = Confirmation.DeleteThreshold(september)))

        compose.onNodeWithText("Delete the thresholds valid from 1 wrz 2026?").assertIsDisplayed()
        tag("confirm-ok").performClick()
        assertEquals(listOf("confirm"), actions.calls)
    }

    // AC5
    @Test
    fun theSheetShowsTheFormItsRulesAndTheBackendsError() {
        show(
            ready.copy(
                thresholdForm = ThresholdForm(editingId = 10, effectiveFrom = "2026-09-01", ftp = "250", invalid = true),
                thresholdError = app.climbra.android.ui.common.UiText(app.climbra.android.R.string.profile_threshold_conflict),
            ),
        )

        tag("threshold-date").assertTextContains("1 wrz 2026")
        tag("threshold-ftp").assertTextContains("250")
        tag("threshold-invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("There already are thresholds valid from this day.").performScrollTo().assertIsDisplayed()
        tag("threshold-save").performScrollTo().performClick()
        assertEquals(listOf("saveThreshold"), actions.calls)
    }

    // AC6
    @Test
    fun zonesShowTheReturnedRangesNamesAndBasis() {
        show(ready)

        contains("profile-power-zones", "Power zones · FTP 270 W")
        contains("power-zones", "Threshold")
        contains("power-zones", "91–105%")
        contains("power-zones", "246–284 W")
        contains("power-zones", "≥ 408 W")
        contains("power-zones", "> 150%")
        contains("profile-hr-zones", "Heart rate zones · LTHR 165 bpm")
        contains("hr-zones", "84–94% LTHR")
        scrolled("chart-ftp").assertIsDisplayed()
    }

    // AC6
    @Test
    fun withoutThresholdsTheZonesExplainWhatIsMissing() {
        show(ready.copy(zones = Section(value = Zones())))

        scrolled("power-zones-empty").assertTextContains("Add your FTP to see power zones.")
        scrolled("hr-zones-empty").assertTextContains("Add your LTHR or max heart rate to see heart rate zones.")
    }

    // AC7
    @Test
    fun intervalsConnectsOnlyWithAKey() {
        show(ready)

        scrolled("intervals-connect").assertIsNotEnabled()
    }

    // AC7
    @Test
    fun intervalsConnectedShowsTheStatusSyncAndDisconnect() {
        show(ready.copy(intervals = Section(value = connected), intervalsThresholds = Section(value = IntervalsThresholds(differsFromCurrent = false))))

        scrolled("intervals-athlete").assertTextContains("Anna Nowak i12345")
        tag("intervals-key").assertTextContains("••••x7Qa")
        scrolled("intervals-sync").performClick()
        scrolled("intervals-disconnect").performClick()
        assertEquals(listOf("syncIntervals", "askDisconnectIntervals"), actions.calls)
    }

    // AC7
    @Test
    fun aRunningSyncDisablesTheButton() {
        show(ready.copy(intervals = Section(value = connected), syncing = true, intervalsThresholds = Section(value = IntervalsThresholds())))

        scrolled("intervals-sync").assertIsNotEnabled()
        tag("intervals-sync").assertTextContains("Syncing…")
    }

    // AC8
    @Test
    fun theProposalComparesWithTheCurrentThresholds() {
        val proposal = IntervalsThresholds(ftpWatts = 280, indoorFtpWatts = 270, lthrBpm = 168, maxHeartRateBpm = 188, differsFromCurrent = true)
        show(ready.copy(intervals = Section(value = connected), intervalsThresholds = Section(value = proposal)))

        scrolled("intervals-proposal").assertIsDisplayed()
        contains("intervals-proposal", "280 W (indoor 270 W)")
        contains("intervals-proposal", "270 W")
        contains("intervals-proposal", "168 bpm")
        scrolled("intervals-propose").performClick()
        assertEquals(listOf("propose:280"), actions.calls)
    }

    // AC8
    @Test
    fun theSameThresholdsOfferNothingToAdd() {
        show(ready.copy(intervals = Section(value = connected), intervalsThresholds = Section(value = IntervalsThresholds(ftpWatts = 270))))

        scrolled("intervals-same").assertIsDisplayed()
        tag("intervals-propose").assertDoesNotExist()
    }

    // AC9
    @Test
    fun garminConnectedShowsItsStatusAndDisconnects() {
        show(ready)

        scrolled("garmin-sent").assertTextContains("4")
        scrolled("garmin-stale").assertIsDisplayed()
        scrolled("garmin-disconnect").performClick()
        assertEquals(listOf("askDisconnectGarmin"), actions.calls)
    }

    // AC9
    @Test
    fun garminNotConnectedConnectsInTheAppAndIsHiddenWhenNotOffered() {
        var state by mutableStateOf(ready.copy(garmin = Section(value = GarminConnection(available = true, status = GarminStatus.NEEDS_RECONNECT))))
        compose.setContent { ClimbraTheme(darkTheme = false) { ProfileScreen(state, polish, today, actions) } }

        scrolled("garmin-problem").assertIsDisplayed()
        // Spec 016 AC5: connecting is in the app, not on climbra.app
        scrolled("garmin-connect").assertTextContains("Connect Garmin Connect").performClick()
        assertEquals(listOf("connectGarmin"), actions.calls)

        state = ready.copy(garmin = Section(value = GarminConnection(available = false)))
        tag("profile-garmin").assertDoesNotExist()
    }

    // AC10, AC11
    @Test
    fun languageAndThemeShowTheChoiceAndChangeIt() {
        show(ready.copy(theme = ThemeMode.DARK))

        scrolled("language-en").assertIsSelected()
        scrolled("theme-dark").assertIsSelected()
        scrolled("language-pl").performClick()
        scrolled("theme-light").performClick()
        assertEquals(listOf("language:pl", "theme:LIGHT"), actions.calls)
    }

    // AC12
    @Test
    fun theAccountShowsTheEmailGoogleAndTheCredits() {
        show(ready)

        scrolled("account-email").assertTextContains("anna@example.com")
        tag("account-google").assertIsDisplayed()
        scrolled("quota-plan").assertTextContains("Free")
        tag("quota-monthly").assertTextContains("25 of 30 · renews on 1 gru 2026")
        tag("quota-bonus").assertTextContains("15 · expire on 31 sty 2027")
        tag("quota-balance").assertTextContains("40")
    }

    // AC12
    @Test
    fun unlimitedCreditsSaySo() {
        show(ready.copy(quota = Section(value = CoachQuota(unlimited = true))))

        scrolled("quota-balance").assertTextContains("unlimited")
    }

    // AC13
    @Test
    fun thePasswordFormShowsTheBrokenRules() {
        show(ready.copy(passwordForm = PasswordForm(current = "", password = "short", repeat = "other", showErrors = true)))

        scrolled("password-current-supporting", unmerged = true).assertTextContains("Required")
        scrolled("password-new-supporting", unmerged = true).assertTextContains("At least 10 characters")
        scrolled("password-repeat-supporting", unmerged = true).assertTextContains("The passwords are different")
        scrolled("password-submit").performClick()
        assertEquals(listOf("changePassword"), actions.calls)
    }

    // AC13
    @Test
    fun anAccountWithoutPasswordGetsTheResetLink() {
        show(ready.copy(account = account.copy(hasPassword = false)))

        scrolled("account-no-password").assertIsDisplayed()
        tag("password-current").assertDoesNotExist()
        scrolled("account-forgot").performClick()
        assertEquals(listOf("forgotPassword"), actions.calls)
    }

    // AC14
    @Test
    fun deletingTheAccountAsksForThePassword() {
        show(ready.copy(deleteAccount = DeleteAccountForm(value = "secret")))

        compose.onNodeWithText("Confirm with your password").assertIsDisplayed()
        tag("delete-account-submit").performClick()
        assertEquals(listOf("deleteAccount"), actions.calls)
    }

    // AC14
    @Test
    fun withoutPasswordTheEmailConfirms() {
        show(ready.copy(account = account.copy(hasPassword = false), deleteAccount = DeleteAccountForm()))

        compose.onNodeWithText("To confirm, type the account's email address (anna@example.com)").assertIsDisplayed()
        tag("delete-account-submit").assertIsNotEnabled()
    }

    // AC14
    @Test
    fun adminsCannotDeleteTheirAccount() {
        show(ready.copy(account = account.copy(role = "ADMIN")))

        scrolled("account-admin-note").assertIsDisplayed()
        tag("account-delete").assertDoesNotExist()
    }

    @Test
    fun logoutStaysAtTheEnd() {
        show(ready)

        scrolled("profile-logout").performClick()
        assertEquals(listOf("logout"), actions.calls)
    }

    // Spec 010 AC1: the account card opens the feedback form
    @Test
    fun theAccountCardOpensTheFeedbackForm() {
        show(ready)

        scrolled("account-feedback").performClick()

        assertEquals(listOf("openFeedback"), actions.calls)
    }

    // Spec 011 AC1, AC2: only an admin sees the Garmin sync; it waits while it runs
    @Test
    fun onlyAnAdminSeesTheGarminSync() {
        var state by mutableStateOf(ready)
        compose.setContent { ClimbraTheme(darkTheme = false) { ProfileScreen(state, polish, today, actions) } }
        tag("profile-garmin-sync").assertDoesNotExist()

        state = ready.copy(account = account.copy(role = "ADMIN"))
        scrolled("garmin-sync").assertIsEnabled().performClick()
        assertEquals(listOf("syncGarmin"), actions.calls)

        state = state.copy(garminSyncing = true)
        scrolled("garmin-sync").assertIsNotEnabled()
    }
}
