package com.xblackbytesx.calzone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.calzone.account.Session
import com.xblackbytesx.calzone.calendar.CalendarScreen
import com.xblackbytesx.calzone.calendar.CalendarRequests
import com.xblackbytesx.calzone.calendar.Intents
import com.xblackbytesx.calzone.calendar.requestOf
import com.xblackbytesx.calzone.reminders.Notices
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.home.HomeScreen
import com.xblackbytesx.calzone.prefs.Prefs
import com.xblackbytesx.calzone.prefs.PrefsStore
import com.xblackbytesx.calzone.prefs.Theme
import com.xblackbytesx.calzone.signin.SignInScreen
import com.xblackbytesx.calzone.ui.CzTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: Session
    @Inject lateinit var prefs: PrefsStore

    /** A sign-in address opened from a camera app or browser. */
    private var link by mutableStateOf<String?>(null)

    /** The person asked to sign in again (signed out on the server). */
    private var signInAgain by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            link = linkOf(intent)
            opened(intent)
        }
        setContent {
            val p by prefs.prefs.collectAsStateWithLifecycle(Prefs())
            val signedIn by session.signedIn.collectAsStateWithLifecycle(null)
            val dark = when (p.theme) {
                Theme.DARK -> true
                Theme.LIGHT -> false
                Theme.SYSTEM -> isSystemInDarkTheme()
            }
            CzTheme(dark = dark) {
                when {
                    signedIn == null -> {}
                    signedIn == false || link != null || signInAgain -> SignInScreen(
                        link = link,
                        canGoBack = signedIn == true,
                        onBack = {
                            link = null
                            signInAgain = false
                        },
                        onDone = {
                            link = null
                            signInAgain = false
                        },
                    )
                    else -> {
                        // The calendar is the app; settings sit behind its button (decision 23).
                        var settings by rememberSaveable { mutableStateOf(false) }
                        // A notification, a widget or another app asked for the calendar.
                        val asked by CalendarRequests.requests.collectAsStateWithLifecycle()
                        LaunchedEffect(asked) { if (asked != null) settings = false }
                        if (settings) {
                            BackHandler { settings = false }
                            HomeScreen(onSignInAgain = { signInAgain = true }, onBack = { settings = false })
                        } else {
                            CalendarScreen(onOpenSettings = { settings = true })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkOf(intent)?.let { link = it }
        opened(intent)
    }

    /** A notification, a widget or another app (decision 34): handed to the calendar screen. */
    private fun opened(intent: Intent?) {
        val r = requestOf(intent) ?: return
        intent?.getIntExtra(Intents.EXTRA_NOTICE, 0)?.takeIf { it != 0 }?.let { Notices.cancel(this, it) }
        CalendarRequests.send(r)
    }

    private fun linkOf(intent: Intent?): String? = intent?.dataString?.takeIf { Core.link(it) != null }
}
