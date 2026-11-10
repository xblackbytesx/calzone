package com.xblackbytesx.calzone.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.xblackbytesx.calzone.home.CollectionRow
import com.xblackbytesx.calzone.home.ConflictRow
import com.xblackbytesx.calzone.home.Explanation
import com.xblackbytesx.calzone.home.FieldLabel
import com.xblackbytesx.calzone.home.HomeActions
import com.xblackbytesx.calzone.home.HomeContent
import com.xblackbytesx.calzone.home.HomeUi
import com.xblackbytesx.calzone.home.PushState
import com.xblackbytesx.calzone.model.Account
import com.xblackbytesx.calzone.model.Report
import com.xblackbytesx.calzone.prefs.Theme
import com.xblackbytesx.calzone.signin.SignInContent
import com.xblackbytesx.calzone.signin.SignInProblem
import com.xblackbytesx.calzone.signin.SignInUi
import com.xblackbytesx.calzone.sync.Problem
import com.xblackbytesx.calzone.sync.SyncStatus
import com.xblackbytesx.calzone.ui.CzTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private object Nothing : HomeActions {
    override fun syncNow() {}
    override fun setShown(id: String, show: Boolean) {}
    override fun dismiss(id: Long) {}
    override fun setPush(on: Boolean) {}
    override fun setTheme(t: Theme) {}
    override fun openNetworkSettings() {}
    override fun allowAccess() {}
    override fun signInAgain() {}
    override fun signOut() {}
}

/** A made-up household (the README screenshots of calzone-server use the same names). */
private val home = HomeUi(
    account = Account("mila", "Mila", "d1", "Google Pixel 9", listOf("ntfy.example.com"), push = true),
    server = "https://calzone.example.com",
    status = SyncStatus(lastOk = 1_793_000_000_000, lastReport = Report(collections = 4)),
    calendars = listOf(
        CollectionRow("1", "Family", "#3a7bd5", "Jonas", readOnly = false, subscribed = false, count = 185, shown = true),
        CollectionRow("2", "Mila", "#d53a7b", null, readOnly = false, subscribed = false, count = 64, shown = true),
        CollectionRow("3", "School holidays", "#e0a800", null, readOnly = true, subscribed = true, count = 22, shown = false),
    ),
    books = listOf(CollectionRow("4", "Contacts", null, null, readOnly = false, subscribed = false, count = 1412, shown = true)),
    pending = 2,
    push = PushState.ON,
)

private val conflict = ConflictRow(
    7,
    "Swimming lesson",
    "Family",
    1_793_000_000,
    listOf(Explanation.Field(FieldLabel.PLACE, "LOCATION", "Outdoor pool", "Sports hall")),
)

/** Every screen with real-looking states, to look at after UI changes (`make screenshots`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("cz.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private fun shot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent { CzTheme(dark = dark) { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").absolutePath)
    }

    @Test
    fun signIn() = shot("sign-in") { SignInContent(SignInUi(), canGoBack = false, {}, {}, {}, {}) }

    @Test
    fun signInUsedUp() = shot("sign-in-used-up") {
        SignInContent(SignInUi(problem = SignInProblem.USED_UP), canGoBack = false, {}, {}, {}, {})
    }

    @Test
    fun homeDark() = shot("home") { HomeContent(home, Nothing) }

    @Test
    fun homeLight() = shot("home-light", dark = false) { HomeContent(home, Nothing) }

    @Test
    fun homeWithConflictAndProblems() = shot("home-problems") {
        HomeContent(
            home.copy(
                networkOk = false,
                systemAccess = false,
                status = home.status.copy(problem = Problem.SIGNED_OUT),
                conflicts = listOf(conflict),
                push = PushState.NO_DISTRIBUTOR,
            ),
            Nothing,
        )
    }

    @Test
    fun homeFirstRun() = shot("home-first-run") {
        HomeContent(home.copy(status = SyncStatus(running = true), calendars = emptyList(), books = emptyList(), pending = 0, push = PushState.SERVER_OFF), Nothing)
    }

    @Test
    @Config(qualifiers = "nl-w411dp-h891dp-xxhdpi")
    fun homeDutch() = shot("home-nl") { HomeContent(home.copy(conflicts = listOf(conflict)), Nothing) }
}
