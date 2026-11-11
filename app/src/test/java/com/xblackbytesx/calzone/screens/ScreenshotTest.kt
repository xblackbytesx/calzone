package com.xblackbytesx.calzone.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.xblackbytesx.calzone.calendar.CalendarActions
import com.xblackbytesx.calzone.calendar.CalendarContent
import com.xblackbytesx.calzone.calendar.CalendarUi
import com.xblackbytesx.calzone.calendar.CalendarView
import com.xblackbytesx.calzone.calendar.CalendarChoice
import com.xblackbytesx.calzone.calendar.DayListContent
import com.xblackbytesx.calzone.calendar.Draft
import com.xblackbytesx.calzone.calendar.EditorActions
import com.xblackbytesx.calzone.calendar.EditorContent
import com.xblackbytesx.calzone.calendar.EditorState
import com.xblackbytesx.calzone.calendar.RepeatEnd
import com.xblackbytesx.calzone.calendar.SearchContent
import com.xblackbytesx.calzone.calendar.SearchUi
import com.xblackbytesx.calzone.calendar.Target
import com.xblackbytesx.calzone.calendar.draftOf
import com.xblackbytesx.calzone.calendar.newDraft
import com.xblackbytesx.calzone.calendar.EventSheetContent
import com.xblackbytesx.calzone.calendar.Sheet
import com.xblackbytesx.calzone.calendar.agendaDays
import com.xblackbytesx.calzone.calendar.dayEntries
import com.xblackbytesx.calzone.calendar.rememberFormats
import com.xblackbytesx.calzone.home.CalendarPrefsContent
import com.xblackbytesx.calzone.home.CollectionRow
import com.xblackbytesx.calzone.home.ConflictRow
import com.xblackbytesx.calzone.home.Explanation
import com.xblackbytesx.calzone.home.FieldLabel
import com.xblackbytesx.calzone.home.HomeActions
import com.xblackbytesx.calzone.home.HomeContent
import com.xblackbytesx.calzone.home.HomeUi
import com.xblackbytesx.calzone.home.PushState
import com.xblackbytesx.calzone.model.Account
import com.xblackbytesx.calzone.model.EventView
import com.xblackbytesx.calzone.model.Mine
import com.xblackbytesx.calzone.model.Occurrence
import com.xblackbytesx.calzone.model.Report
import com.xblackbytesx.calzone.prefs.Theme
import com.xblackbytesx.calzone.signin.SignInContent
import com.xblackbytesx.calzone.signin.SignInProblem
import com.xblackbytesx.calzone.signin.SignInUi
import com.xblackbytesx.calzone.sync.Problem
import com.xblackbytesx.calzone.sync.SyncStatus
import com.xblackbytesx.calzone.ui.Cz
import com.xblackbytesx.calzone.ui.CzTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
    override fun setMine(id: String, mine: Mine) {}
    override fun dismiss(id: Long) {}
    override fun setPush(on: Boolean) {}
    override fun setTheme(t: Theme) {}
    override fun openNetworkSettings() {}
    override fun openNotificationSettings() {}
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

private object NoAgenda : CalendarActions {
    override fun open(o: Occurrence) {}
    override fun closeSheet() {}
    override fun dismiss(conflict: Long) {}
    override fun refresh() {}
    override fun loadEarlier() {}
    override fun loadLater() {}
    override fun openSettings() {}
    override fun setView(view: CalendarView) {}
    override fun goTo(date: LocalDate) {}
    override fun show(date: LocalDate) {}
    override fun seen(date: LocalDate) {}
    override fun openDay(date: LocalDate) {}
    override fun closeDay() {}
    override fun dayView(date: LocalDate) {}
    override fun openSearch() {}
    override fun search(query: String) {}
    override fun closeSearch() {}
    override fun newEvent(start: LocalDateTime?) {}
    override fun edit() {}
    override fun delete() {}
    override fun answer(scope: String?) {}
    override fun undo(id: Long) {}
    override fun toastDone() {}
    override val editor = NoEditor
}

private object NoEditor : EditorActions {
    override fun change(d: Draft) {}
    override fun quick(text: String) {}
    override fun save() {}
    override fun close() {}
}

private val calendars = listOf(
    CalendarChoice("2", "Mila", "#d53a7b", 15),
    CalendarChoice("1", "Family", "#3a7bd5", null),
)

private val today = LocalDate.parse("2026-11-11")

/** A made-up week: the family calendar is Jonas's, Mila's own is hers. */
private fun occ(title: String, start: String, end: String, place: String? = null, family: Boolean = false, allDay: Boolean = false, pending: Boolean = false) =
    Occurrence(
        collection = if (family) "1" else "2",
        name = "$title.ics",
        title = title,
        location = place,
        start = start,
        end = end,
        allDay = allDay,
        calendar = if (family) "Family" else "Mila",
        color = if (family) "#3a7bd5" else "#d53a7b",
        owner = if (family) "Jonas de Vries" else "Mila",
        initials = if (family) "JV" else "MI",
        own = !family,
        pending = pending,
    )

private val week = listOf(
    occ("Dentist", "2026-11-10T09:00:00", "2026-11-10T09:30:00", "Main street 12"),
    occ("Grandma's birthday", "2026-11-11", "2026-11-12", family = true, allDay = true),
    occ("Swimming lesson", "2026-11-11T16:00:00", "2026-11-11T17:00:00", "Sports hall", family = true),
    occ("Book club", "2026-11-11T20:00:00", "2026-11-11T22:00:00", pending = true),
    occ("Parents' evening", "2026-11-12T19:30:00", "2026-11-12T20:30:00", "School", family = true),
    occ("Weekend in Ghent", "2026-11-14T10:00:00", "2026-11-16T15:00:00", family = true),
    occ("Swimming lesson", "2026-11-18T16:00:00", "2026-11-18T17:00:00", "Sports hall", family = true),
    occ("Call with Sam", "2026-11-11T16:30:00", "2026-11-11T17:15:00"),
    occ("Pick up parcel", "2026-11-11T16:45:00", "2026-11-11T17:00:00", family = true),
    occ("Yoga", "2026-11-13T07:30:00", "2026-11-13T08:30:00"),
    occ("Night shift", "2026-11-12T22:00:00", "2026-11-13T06:00:00"),
    occ("School trip", "2026-11-19", "2026-11-21", family = true, allDay = true),
    occ("Team dinner", "2026-11-20T18:30:00", "2026-11-20T21:00:00", "De Pijp"),
    occ("Autumn holiday", "2026-11-23", "2026-11-28", family = true, allDay = true),
    occ("Car to the garage", "2026-11-24T08:00:00", "2026-11-24T08:30:00"),
    occ("Piano", "2026-11-11T18:00:00", "2026-11-11T18:45:00", family = true),
    occ("Library books back", "2026-11-11", "2026-11-12", allDay = true),
    occ("Payday", "2026-11-25", "2026-11-26", allDay = true),
    occ("Swimming lesson", "2026-11-25T16:00:00", "2026-11-25T17:00:00", "Sports hall", family = true),
)

private val from = LocalDate.parse("2026-09-01")
private val to = LocalDate.parse("2027-01-01")

private val agenda = CalendarUi(
    today = today,
    now = LocalTime.of(16, 50),
    anchor = today,
    from = from,
    to = to,
    items = week,
    days = agendaDays(week.filter { it.start >= "2026-11-08" && it.start < "2026-11-22" }, today.minusDays(3), today.plusDays(10), today),
    loading = false,
    writable = calendars,
    legend = calendars,
    view = CalendarView.AGENDA,
)

private val swim = EventView(
    collection = "1",
    name = "swim.ics",
    instance = "20261111T160000",
    title = "Swimming lesson",
    location = "Sports hall",
    description = "Bring the green towel.",
    startDate = "2026-11-11",
    startTime = "16:00",
    endDate = "2026-11-11",
    endTime = "17:00",
    repeat = "weekly",
    repeatUntil = "2027-07-07",
    recurring = true,
    alarms = listOf(60, 15),
    calendar = "Family",
    color = "#3a7bd5",
    owner = "Jonas de Vries",
    initials = "JV",
    own = false,
)

/** Every screen with real-looking states, to look at after UI changes (`make screenshots`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("cz.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private fun shot(name: String, dark: Boolean = true, black: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { CzTheme(dark = dark, black = black) { content() } }
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
                remindersSilenced = true,
                version = "0.2.0",
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

    @Test
    fun agendaDark() = shot("agenda") { CalendarContent(agenda, NoAgenda) }

    @Test
    fun agendaLight() = shot("agenda-light", dark = false) { CalendarContent(agenda, NoAgenda) }

    @Test
    @Config(qualifiers = "nl-w411dp-h891dp-xxhdpi")
    fun agendaDutch() = shot("agenda-nl") { CalendarContent(agenda.copy(attention = true, conflicts = listOf(conflict)), NoAgenda) }

    @Test
    fun day() = shot("day") { CalendarContent(agenda.copy(view = CalendarView.DAY), NoAgenda) }

    @Test
    fun weekDark() = shot("week") { CalendarContent(agenda.copy(view = CalendarView.WEEK), NoAgenda) }

    @Test
    // nl-rNL as on the phones: a week starts on Monday there.
    @Config(qualifiers = "nl-rNL-w411dp-h891dp-xxhdpi")
    fun weekDutch() = shot("week-nl", dark = false) { CalendarContent(agenda.copy(view = CalendarView.WEEK), NoAgenda) }

    @Test
    fun monthDayBlack() = shot("month-day-black", black = true) { CalendarContent(agenda.copy(view = CalendarView.MONTH_DAY), NoAgenda) }

    @Test
    fun homeBlack() = shot("home-black", black = true) { HomeContent(home.copy(theme = Theme.BLACK), Nothing) }

    @Test
    fun monthDay() = shot("month-day") { CalendarContent(agenda.copy(view = CalendarView.MONTH_DAY), NoAgenda) }

    @Test
    @Config(qualifiers = "nl-rNL-w411dp-h891dp-xxhdpi")
    fun monthDayDutch() = shot("month-day-nl", dark = false) {
        CalendarContent(agenda.copy(view = CalendarView.MONTH_DAY, anchor = LocalDate.parse("2026-11-19")), NoAgenda)
    }

    @Test
    fun month() = shot("month") { CalendarContent(agenda.copy(view = CalendarView.MONTH), NoAgenda) }

    @Test
    // nl-rNL as on the phones: a week starts on Monday there.
    @Config(qualifiers = "nl-rNL-w411dp-h891dp-xxhdpi")
    fun monthDutch() = shot("month-nl", dark = false) { CalendarContent(agenda.copy(view = CalendarView.MONTH), NoAgenda) }

    @Test
    fun editorNew() = shot("editor-new") {
        val d = newDraft("2", LocalDateTime.parse("2026-11-11T18:00"), allDay = false, alarms = listOf(15)).copy(title = "Dentist")
        EditorContent(EditorState(d, d), calendars, rememberFormats(), NoEditor)
    }

    @Test
    @Config(qualifiers = "nl-rNL-w411dp-h891dp-xxhdpi")
    fun editorRepeatDutch() = shot("editor-nl") {
        val d = draftOf(swim)
        EditorContent(
            EditorState(d, d.copy(repeatEnd = RepeatEnd.UNTIL, until = LocalDate.parse("2027-07-07")), Target("1", "swim.ics", swim.instance), recurring = true),
            calendars,
            rememberFormats(),
            NoEditor,
        )
    }

    @Test
    fun editorRemindersOnly() = shot("editor-reminders", dark = false) {
        val d = draftOf(swim.copy(editable = false))
        EditorContent(EditorState(d, d, Target("1", "swim.ics", null), remindersOnly = true), calendars, rememberFormats(), NoEditor)
    }

    @Test
    fun calendarPrefs() = shot("calendar-prefs") {
        Box(Modifier.fillMaxSize().background(Cz.colors.canvas)) {
            CalendarPrefsContent(home.calendars.first().copy(mine = Mine(name = "Our family", defaultAlarm = 60))) {}
        }
    }

    @Test
    fun search() = shot("search") {
        SearchContent(
            SearchUi("swim", listOf(week[2], week[6], week.last(), week[0].copy(title = "Swimming gear", start = "2026-10-02T09:00:00", end = "2026-10-02T09:30:00"))),
            today,
            rememberFormats(),
            NoAgenda,
        )
    }

    @Test
    fun dayList() = shot("day-list") {
        Box(Modifier.fillMaxSize().background(Cz.colors.canvas)) {
            DayListContent(dayEntries(week, today), today, rememberFormats(), NoAgenda)
        }
    }

    @Test
    fun eventSheet() = shot("event-sheet") {
        Box(Modifier.fillMaxSize().background(Cz.colors.surface)) {
            EventSheetContent(Sheet(swim, listOf(conflict)), today, rememberFormats()) {}
        }
    }

    @Test
    @Config(qualifiers = "nl-w411dp-h891dp-xxhdpi")
    fun eventSheetDutch() = shot("event-sheet-nl", dark = false) {
        Box(Modifier.fillMaxSize().background(Cz.colors.surface)) {
            EventSheetContent(Sheet(swim, emptyList()), today, rememberFormats()) {}
        }
    }
}
