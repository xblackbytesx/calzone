package com.xblackbytesx.calzone.calendar

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarViewDay
import androidx.compose.material.icons.rounded.CalendarViewMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.bridge.Bridges
import com.xblackbytesx.calzone.bridge.SystemEvents
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.home.ConflictCard
import com.xblackbytesx.calzone.home.ConflictRow
import com.xblackbytesx.calzone.home.conflictRows
import com.xblackbytesx.calzone.home.explanationText
import com.xblackbytesx.calzone.model.EventView
import com.xblackbytesx.calzone.model.Occurrence
import com.xblackbytesx.calzone.prefs.Network
import com.xblackbytesx.calzone.prefs.PrefsStore
import com.xblackbytesx.calzone.reminders.Notices
import com.xblackbytesx.calzone.reminders.Reminders
import com.xblackbytesx.calzone.widget.Widgets
import com.xblackbytesx.calzone.sync.Problem
import com.xblackbytesx.calzone.sync.SyncRunner
import com.xblackbytesx.calzone.sync.SyncWorker
import com.xblackbytesx.calzone.ui.Cz
import com.xblackbytesx.calzone.ui.parseColor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The event sheet: the event, and what a merge said about it (decision 25). */
data class Sheet(val event: EventView?, val conflicts: List<ConflictRow>)

/** A request to bring `date` into sight (Today, the date picker, another view); `seq` makes each one new. */
data class Jump(val date: LocalDate, val seq: Int = 0)

data class CalendarUi(
    val today: LocalDate = LocalDate.now(),
    /** The time of day, for the line across today in the hours. */
    val now: LocalTime = LocalTime.now(),
    val view: CalendarView = CalendarView.MONTH_DAY,
    /** The date in sight: the agenda's top day, or the day, week or month shown. */
    val anchor: LocalDate = today,
    val jump: Jump = Jump(anchor),
    /** The occurrences loaded, for `[from, to)`. */
    val from: LocalDate = today,
    val to: LocalDate = today,
    val items: List<Occurrence> = emptyList(),
    val days: List<AgendaDay> = emptyList(),
    /** [days] by date, for the grids (decision 37). */
    val byDate: DayIndex = emptyMap(),
    /** The first load has not finished (the core expands every event once). */
    val loading: Boolean = true,
    val syncing: Boolean = false,
    /** Something in Settings needs the person (signed out, no network, no access). */
    val attention: Boolean = false,
    val conflicts: List<ConflictRow> = emptyList(),
    /** At least one calendar is on this phone. */
    val hasCalendars: Boolean = true,
    val sheet: Sheet? = null,
    /** A day's list, opened from the month or a "+2". */
    val day: LocalDate? = null,
    /** Calendars a new event can go in, the one used last first. */
    val writable: List<CalendarChoice> = emptyList(),
    /** The calendars this phone shows, for the legend (decision 33). */
    val legend: List<CalendarChoice> = emptyList(),
    /** Legend calendars toggled off here: left out of the views, still synced (decision 38). */
    val dimmed: Set<String> = emptySet(),
    val editor: EditorState? = null,
    /** The scope question is open: for a delete, or for saving the editor. */
    val ask: Ask? = null,
    val toast: Toast? = null,
    /** A reminder is set and notifications were never asked for: ask now (decision 32). */
    val askNotifications: Boolean = false,
    /** Search is open (decision 34). */
    val search: SearchUi? = null,
)

/** Search: what was typed and what was found (null while the first search runs). */
data class SearchUi(val query: String = "", val results: List<Occurrence>? = emptyList())

/** A scope question waiting for its answer. */
sealed interface Ask {
    data class Delete(val target: Target) : Ask
    data object Save : Ask
}

/** The bar at the bottom after a write: what happened, and Undo for 10 seconds (no confirm dialogs). */
data class Toast(@androidx.annotation.StringRes val text: Int, val undo: Long? = null, val detail: String? = null, val seq: Long = System.nanoTime())

interface CalendarActions {
    fun open(o: Occurrence)
    fun closeSheet()
    fun dismiss(conflict: Long)
    fun refresh()
    fun loadEarlier()
    fun loadLater()
    fun openSettings()

    /** A legend calendar tapped: hide it from the views, or show it again (decision 38). */
    fun toggleCalendar(id: String)
    fun setView(view: CalendarView)

    /** Today, the date picker: go there, in any view. */
    fun goTo(date: LocalDate)

    /** A day, week or month swiped into sight. */
    fun show(date: LocalDate)

    /** The agenda's top day. */
    fun seen(date: LocalDate)
    fun openDay(date: LocalDate)
    fun closeDay()

    /** One day in the day view (a week's day heading, a day's list). */
    fun dayView(date: LocalDate)

    fun openSearch()
    fun search(query: String)
    fun closeSearch()

    /** A new event: from the "+" (`start` null), or a long press on an hour or a day. */
    fun newEvent(start: LocalDateTime?)

    /** The open sheet's event in the editor. */
    fun edit()

    /** Delete the open sheet's event (asks which ones for a recurring one). */
    fun delete()
    fun answer(scope: String?)
    fun undo(id: Long)
    fun toastDone()
    val editor: EditorActions
}

/** How far the agenda reaches before it loads more, in days. */
private const val STEP = 90L

/** The grids load around the date in sight: this far back and ahead (decision 37: wide, so swiping rarely reloads). */
private const val BACK = 120L
private const val AHEAD = 240L

@HiltViewModel
class CalendarViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val core: Core,
    private val runner: SyncRunner,
    private val prefsStore: PrefsStore,
    private val bridges: Bridges,
    private val saved: SavedStateHandle,
    private val reminders: Reminders,
) : ViewModel() {
    private val _ui: MutableStateFlow<CalendarUi>
    val ui: StateFlow<CalendarUi>
    private var from: LocalDate
    private var to: LocalDate
    private val loads = Mutex()
    private var signedOut = false

    init {
        // The view and date survive rotation and the system ending the app.
        val view = saved.get<String>(VIEW)?.let { runCatching { CalendarView.valueOf(it) }.getOrNull() }
        val anchor = saved.get<String>(ANCHOR)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        from = anchor.minusDays(BACK)
        to = anchor.plusDays(AHEAD)
        _ui = MutableStateFlow(CalendarUi(view = view ?: CalendarView.MONTH_DAY, anchor = anchor, jump = Jump(anchor)))
        ui = _ui
        if (view == null) {
            // A fresh start opens on today, in the view chosen last.
            viewModelScope.launch {
                prefsStore.prefs.first().view?.let { runCatching { CalendarView.valueOf(it) }.getOrNull() }?.let { v ->
                    if (saved.get<String>(VIEW) == null) _ui.update { it.copy(view = v) }
                }
            }
        }
        // Again after every run, and when this phone's choices change.
        viewModelScope.launch {
            runner.status.map { Triple(it.lastOk, it.running, it.problem) }.distinctUntilChanged().collect { (_, running, problem) ->
                signedOut = problem == Problem.SIGNED_OUT
                _ui.update { it.copy(syncing = running) }
                look()
                reload()
            }
        }
        viewModelScope.launch { prefsStore.prefs.map { Triple(it.shown, it.hidden, it.dimmed) }.distinctUntilChanged().collect { reload() } }
        // A notification, a widget or another app asked for something (decision 34).
        viewModelScope.launch {
            CalendarRequests.requests.filterNotNull().collect { r ->
                CalendarRequests.taken()
                handle(r)
            }
        }
        // The line across today moves with the clock.
        viewModelScope.launch {
            while (true) {
                val n = LocalDateTime.now()
                delay(60_000L - n.second * 1000L - n.nano / 1_000_000L)
                val t = LocalDateTime.now()
                _ui.update { it.copy(now = t.toLocalTime(), today = t.toLocalDate()) }
            }
        }
    }

    /** On every resume: the day may have changed, and so may the phone's state. */
    fun resumed() {
        val now = LocalDateTime.now()
        val dayChanged = now.toLocalDate() != _ui.value.today
        _ui.update { it.copy(today = now.toLocalDate(), now = now.toLocalTime()) }
        if (dayChanged) goTo(now.toLocalDate())
        look()
        reload()
    }

    private fun look() {
        // Reminders set while notifications are off would never show.
        val silenced = reminders.anyComing() && !Notices.allowed(context)
        _ui.update { it.copy(attention = signedOut || !Network.reachable(context) || !bridges.permitted() || silenced) }
        viewModelScope.launch {
            val ask = silenced && !prefsStore.prefs.first().askedNotifications
            _ui.update { it.copy(askNotifications = ask) }
        }
    }

    /** The notification permission was answered (or the question was dismissed). */
    fun notificationsAsked() {
        _ui.update { it.copy(askNotifications = false) }
        viewModelScope.launch {
            prefsStore.setAskedNotifications()
            look()
        }
    }

    fun reload() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() = loads.withLock {
        val prefs = prefsStore.prefs.first()
        val zone = ZoneId.systemDefault()
        val (f, t) = from to to
        val today = _ui.value.today
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val collections = core.collections()
                val calendars = collections.filter { it.info.isCalendar }
                val hidden = calendars.filter { !prefs.shows(it.info.id, it.info.mine.hidden) }.map { it.info.id }
                // Toggled off in the legend: out of the views only (decision 38).
                val dimmed = calendars.map { it.info.id }.filter { it in prefs.dimmed && it !in hidden }
                val items = core.occurrences(
                    f.atStartOfDay(zone).toEpochSecond(),
                    t.atStartOfDay(zone).toEpochSecond(),
                    zone.id,
                    hidden + dimmed,
                )
                // Contacts' conflicts stay in Settings; a gone calendar's show here too.
                val books = collections.filter { !it.info.isCalendar }.map { it.info.id }.toSet()
                val conflicts = conflictRows(core, collections).filter { it.collectionId !in books }
                val writable = calendars.filter { !it.info.readOnly }
                    .map { CalendarChoice(it.info.id, it.info.label, it.info.shownColor, it.info.mine.defaultAlarm) }
                    .sortedBy { if (it.id == prefs.lastCalendar) 0 else 1 }
                val legend = calendars.filter { it.info.id !in hidden }
                    .map { CalendarChoice(it.info.id, it.info.label, it.info.shownColor, it.info.mine.defaultAlarm) }
                // Grouped here, not on the main thread (decision 37).
                val days = agendaDays(items, f, t, today)
                Loaded(items, conflicts, calendars.size > hidden.size, writable, legend, dimmed.toSet(), days, days.associateBy { it.date })
            }.getOrNull()
        } ?: return@withLock
        val (items, conflicts, any, writable, legend) = loaded
        _ui.update {
            it.copy(
                from = f,
                to = t,
                items = items,
                days = loaded.days,
                byDate = loaded.byDate,
                loading = false,
                conflicts = conflicts,
                hasCalendars = any,
                writable = writable,
                legend = legend,
                dimmed = loaded.dimmed,
            )
        }
    }

    private data class Loaded(
        val items: List<Occurrence>,
        val conflicts: List<ConflictRow>,
        val any: Boolean,
        val writable: List<CalendarChoice>,
        val legend: List<CalendarChoice>,
        val dimmed: Set<String>,
        val days: List<AgendaDay>,
        val byDate: DayIndex,
    )

    /** Shown at once in the legend; the views follow when the load finishes. */
    fun toggleCalendar(id: String) {
        val dim = id !in _ui.value.dimmed
        _ui.update { it.copy(dimmed = if (dim) it.dimmed + id else it.dimmed - id) }
        viewModelScope.launch { prefsStore.setDimmed(id, dim) }
    }

    fun loadEarlier() {
        from = from.minusDays(STEP)
        reload()
    }

    fun loadLater() {
        to = to.plusDays(STEP)
        reload()
    }

    /** Loads around `date` when what is loaded does not reach far enough for the view. */
    private fun cover(date: LocalDate, view: CalendarView) {
        val enough = if (view == CalendarView.AGENDA) {
            date >= from && date < to
        } else {
            from <= date.minusDays(60) && to >= date.plusDays(120)
        }
        if (enough) return
        from = date.minusDays(BACK)
        to = date.plusDays(AHEAD)
        reload()
    }

    private fun keep(view: CalendarView, anchor: LocalDate) {
        saved[VIEW] = view.name
        saved[ANCHOR] = anchor.toString()
    }

    fun setView(view: CalendarView) {
        val u = _ui.updateAndGet { it.copy(view = view, jump = Jump(it.anchor, it.jump.seq + 1), day = null) }
        keep(view, u.anchor)
        viewModelScope.launch { prefsStore.setView(view.name) }
        cover(u.anchor, view)
    }

    fun goTo(date: LocalDate) {
        val u = _ui.updateAndGet { it.copy(anchor = date, jump = Jump(date, it.jump.seq + 1)) }
        keep(u.view, date)
        cover(date, u.view)
    }

    fun show(date: LocalDate) {
        if (date == _ui.value.anchor) return
        val u = _ui.updateAndGet { it.copy(anchor = date) }
        keep(u.view, date)
        cover(date, u.view)
    }

    fun seen(date: LocalDate) {
        if (date == _ui.value.anchor) return
        val u = _ui.updateAndGet { it.copy(anchor = date) }
        keep(u.view, date)
    }

    fun dayView(date: LocalDate) {
        _ui.update { it.copy(anchor = date, day = null) }
        setView(CalendarView.DAY)
    }

    fun openDay(date: LocalDate) = _ui.update { it.copy(day = date) }

    fun closeDay() = _ui.update { it.copy(day = null) }

    fun open(o: Occurrence) {
        _ui.update { it.copy(search = null) }
        openTarget(Target(o.collection, o.name, o.instance))
    }

    private fun openTarget(t: Target, edit: Boolean = false) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault().id
            val event = withContext(Dispatchers.IO) { runCatching { core.event(t.collection, t.name, t.instance, zone) }.getOrNull() }
            val about = _ui.value.conflicts.filter { it.collectionId == t.collection && it.name == t.name }
            _ui.update { it.copy(sheet = Sheet(event, about), day = null, editor = null, search = null) }
            if (edit && event != null) edit()
        }
    }

    private fun handle(r: Request) {
        when (r) {
            is Request.Open -> openTarget(r.target, r.edit)
            is Request.Day -> {
                _ui.update { it.copy(sheet = null, editor = null, search = null) }
                goTo(r.date)
            }
            is Request.Insert -> viewModelScope.launch {
                // The first load tells which calendars can take it.
                val u = _ui.first { !it.loading }
                val cal = u.writable.firstOrNull()
                if (cal == null) {
                    _ui.update { it.copy(toast = Toast(R.string.no_writable_calendar)) }
                    return@launch
                }
                val blank = newDraft(cal.id, newStart(u.today, u.today, u.now), allDay = false, alarms = listOfNotNull(cal.defaultAlarm?.toLong()))
                val filled = draftFrom(r.prefill, cal.id, blank.start, blank.alarms)
                _ui.update { it.copy(editor = EditorState(blank, filled), sheet = null, search = null) }
            }
            is Request.Row -> viewModelScope.launch {
                val target = withContext(Dispatchers.IO) {
                    runCatching {
                        val ref = SystemEvents.find(context, r.id) ?: return@runCatching null
                        // Which occurrence: the one starting when the other app said (or the row's own).
                        val at = (r.beginMs ?: ref.originalMs)?.div(1000)
                        val instance = at?.let { s ->
                            core.occurrences(s - 60, s + 60, ZoneId.systemDefault().id, emptyList())
                                .filter { it.collection == ref.collection && it.name == ref.name }
                                .minByOrNull { kotlin.math.abs(it.at - s) }?.instance
                        }
                        Target(ref.collection, ref.name, instance)
                    }.getOrNull()
                }
                if (target == null) _ui.update { it.copy(toast = Toast(R.string.not_ours)) } else openTarget(target, r.edit)
            }
        }
    }

    // --- search (decision 34) ---

    private var searching: kotlinx.coroutines.Job? = null

    fun openSearch() = _ui.update { it.copy(search = SearchUi()) }

    fun closeSearch() {
        searching?.cancel()
        _ui.update { it.copy(search = null) }
    }

    fun search(query: String) {
        _ui.update { u -> u.copy(search = u.search?.copy(query = query) ?: SearchUi(query)) }
        searching?.cancel()
        searching = viewModelScope.launch {
            if (query.isBlank()) {
                _ui.update { u -> u.copy(search = u.search?.copy(results = emptyList())) }
                return@launch
            }
            // Typing on: search when it pauses.
            delay(250)
            _ui.update { u -> u.copy(search = u.search?.copy(results = u.search.results.takeIf { !it.isNullOrEmpty() })) }
            val prefs = prefsStore.prefs.first()
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val hidden = core.collections().filter { it.info.isCalendar && !prefs.shows(it.info.id, it.info.mine.hidden) }.map { it.info.id }
                    core.search(query, ZoneId.systemDefault().id, hidden)
                }.getOrDefault(emptyList())
            }
            _ui.update { u -> if (u.search?.query == query) u.copy(search = u.search.copy(results = found)) else u }
        }
    }

    fun closeSheet() = _ui.update { it.copy(sheet = null) }

    // --- editing (decision 30): every write is a pending edit, with Undo for 10 seconds ---

    private val zone: String get() = ZoneId.systemDefault().id

    private val lang: String get() = if (context.resources.configuration.locales[0].language == "nl") "nl" else "en"

    fun newEvent(start: LocalDateTime?) {
        val u = _ui.value
        val cal = u.writable.firstOrNull() ?: return
        val at = start ?: newStart(if (u.view == CalendarView.AGENDA) u.today else u.anchor, u.today, u.now)
        val d = newDraft(cal.id, at, allDay = false, alarms = listOfNotNull(cal.defaultAlarm?.toLong()))
        _ui.update { it.copy(editor = EditorState(d, d), day = null) }
    }

    fun edit() {
        val e = _ui.value.sheet?.event ?: return
        val d = draftOf(e)
        val target = Target(e.collection, e.name, e.instance)
        _ui.update { it.copy(editor = EditorState(d, d, target, recurring = e.recurring, remindersOnly = !e.editable)) }
    }

    fun change(d: Draft) = _ui.update { it.copy(editor = it.editor?.copy(draft = d)) }

    fun quick(text: String) {
        viewModelScope.launch {
            val q = withContext(Dispatchers.IO) { runCatching { core.quickAdd(text, lang, zone) }.getOrNull() }
            if (q == null) {
                _ui.update { it.copy(toast = Toast(R.string.quick_none)) }
                return@launch
            }
            _ui.update { u -> u.copy(editor = u.editor?.let { it.copy(draft = it.draft.filled(q)) }) }
        }
    }

    fun closeEditor() = _ui.update { it.copy(editor = null, ask = null) }

    fun save() {
        val ed = _ui.value.editor ?: return
        if (!ed.remindersOnly && ed.draft.title.isBlank()) {
            _ui.update { it.copy(toast = Toast(R.string.need_title)) }
            return
        }
        val body = changeBody(ed.draft, ed.initial, ed.isNew)
        when {
            ed.isNew -> write(R.string.added) {
                viewModelScope.launch { prefsStore.setLastCalendar(ed.draft.collection) }
                core.createEvent(ed.draft.collection, body.toString(), zone, newUid())
            }
            body.isEmpty() -> closeEditor()
            ed.recurring && !remindersOnly(body) -> _ui.update { it.copy(ask = Ask.Save) }
            else -> saveWith("all")
        }
    }

    private fun saveWith(scope: String) {
        val ed = _ui.value.editor ?: return
        val t = ed.target ?: return
        val body = changeBody(ed.draft, ed.initial, false)
        write(R.string.saved) { core.updateEvent(t.collection, t.name, t.instance, scope, body.toString(), zone, newUid()) }
    }

    fun delete() {
        val e = _ui.value.sheet?.event ?: return
        val t = Target(e.collection, e.name, e.instance)
        if (e.recurring) _ui.update { it.copy(ask = Ask.Delete(t)) } else deleteWith(t, "all")
    }

    private fun deleteWith(t: Target, scope: String) = write(R.string.deleted) { core.deleteEvent(t.collection, t.name, t.instance, scope) }

    fun answer(scope: String?) {
        val ask = _ui.value.ask
        _ui.update { it.copy(ask = null) }
        if (scope == null) return
        when (ask) {
            is Ask.Delete -> deleteWith(ask.target, scope)
            Ask.Save -> saveWith(scope)
            null -> Unit
        }
    }

    /** A write: on success the sheets close, the bar offers Undo, and a sync sends it (the bridge mirrors it into Android). */
    private fun write(@androidx.annotation.StringRes done: Int, block: () -> com.xblackbytesx.calzone.model.Written) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            r.onSuccess { w ->
                _ui.update { it.copy(editor = null, sheet = null, ask = null, toast = Toast(done, undo = w.undo)) }
                reload()
                // The next reminder now, not after the sync (which may wait for a network).
                reminders.reschedule()
                Widgets.updateSoon(context)
                look()
                SyncWorker.now(context)
            }.onFailure { e ->
                _ui.update { it.copy(ask = null, toast = Toast(R.string.write_failed, detail = e.message)) }
            }
        }
    }

    fun undo(id: Long) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { core.undo(id) }.getOrDefault(false) }
            _ui.update { it.copy(toast = Toast(if (ok) R.string.undone else R.string.cannot_undo)) }
            if (ok) {
                reload()
                reminders.reschedule()
                Widgets.updateSoon(context)
                SyncWorker.now(context)
            }
        }
    }

    fun toastDone() = _ui.update { it.copy(toast = null) }

    private fun newUid(): String = java.util.UUID.randomUUID().toString()

    fun dismiss(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { core.dismissConflict(id) } }
            _ui.update { u -> u.copy(sheet = u.sheet?.let { s -> s.copy(conflicts = s.conflicts.filter { it.id != id }) }) }
            reload()
        }
    }

    fun refresh() = SyncWorker.now(context)

    private companion object {
        const val VIEW = "view"
        const val ANCHOR = "anchor"
    }
}

private fun <T> MutableStateFlow<T>.updateAndGet(f: (T) -> T): T {
    update(f)
    return value
}

/** The calendar: the app's main screen (decision 23). */
@Composable
fun CalendarScreen(onOpenSettings: () -> Unit, vm: CalendarViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.resumed() }
    // Asked once, with the first reminder (decision 32); a refusal shows in Settings.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.notificationsAsked() }
    LaunchedEffect(ui.askNotifications) {
        if (ui.askNotifications) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    CalendarContent(
        ui,
        object : CalendarActions {
            override fun open(o: Occurrence) = vm.open(o)
            override fun closeSheet() = vm.closeSheet()
            override fun dismiss(conflict: Long) = vm.dismiss(conflict)
            override fun refresh() = vm.refresh()
            override fun loadEarlier() = vm.loadEarlier()
            override fun loadLater() = vm.loadLater()
            override fun openSettings() = onOpenSettings()
            override fun toggleCalendar(id: String) = vm.toggleCalendar(id)
            override fun setView(view: CalendarView) = vm.setView(view)
            override fun goTo(date: LocalDate) = vm.goTo(date)
            override fun show(date: LocalDate) = vm.show(date)
            override fun seen(date: LocalDate) = vm.seen(date)
            override fun openDay(date: LocalDate) = vm.openDay(date)
            override fun closeDay() = vm.closeDay()
            override fun dayView(date: LocalDate) = vm.dayView(date)
            override fun openSearch() = vm.openSearch()
            override fun search(query: String) = vm.search(query)
            override fun closeSearch() = vm.closeSearch()
            override fun newEvent(start: LocalDateTime?) = vm.newEvent(start)
            override fun edit() = vm.edit()
            override fun delete() = vm.delete()
            override fun answer(scope: String?) = vm.answer(scope)
            override fun undo(id: Long) = vm.undo(id)
            override fun toastDone() = vm.toastDone()
            override val editor = object : EditorActions {
                override fun change(d: Draft) = vm.change(d)
                override fun quick(text: String) = vm.quick(text)
                override fun save() = vm.save()
                override fun close() = vm.closeEditor()
            }
        },
    )
}

/** The month the title names: for a week, the month most of it is in. */
private fun titleDate(ui: CalendarUi, f: Formats): LocalDate =
    if (ui.view == CalendarView.WEEK) weekStart(ui.anchor, f.firstDay).plusDays(3) else ui.anchor

private val CalendarView.icon: ImageVector
    get() = when (this) {
        CalendarView.MONTH_DAY -> Icons.Rounded.CalendarViewDay
        CalendarView.AGENDA -> Icons.Rounded.ViewAgenda
        CalendarView.DAY -> Icons.Rounded.ViewDay
        CalendarView.WEEK -> Icons.Rounded.ViewWeek
        CalendarView.MONTH -> Icons.Rounded.CalendarViewMonth
    }

@Composable
private fun viewName(v: CalendarView): String = stringResource(
    when (v) {
        CalendarView.MONTH_DAY -> R.string.view_month_day
        CalendarView.AGENDA -> R.string.view_agenda
        CalendarView.DAY -> R.string.view_day
        CalendarView.WEEK -> R.string.view_week
        CalendarView.MONTH -> R.string.view_month
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarContent(ui: CalendarUi, actions: CalendarActions) {
    val c = Cz.colors
    val f = rememberFormats()
    var showConflicts by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val toastText = ui.toast?.let { t -> stringResource(t.text) + (t.detail?.let { " $it" } ?: "") }
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(ui.toast?.seq) {
        val t = ui.toast ?: return@LaunchedEffect
        // Undo stays for 10 seconds, as on the web; a plain message goes sooner.
        val result = withTimeoutOrNull(if (t.undo != null) 10_000L else 4_000L) {
            snack.showSnackbar(toastText ?: "", actionLabel = if (t.undo != null) undoLabel else null, duration = SnackbarDuration.Indefinite)
        }
        if (result == SnackbarResult.ActionPerformed) t.undo?.let(actions::undo) else actions.toastDone()
    }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // The title opens the date picker.
            val pick = stringResource(R.string.pick_date)
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { picking = true }.padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { contentDescription = pick },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    f.monthTitle(titleDate(ui, f), ui.today),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Rounded.ArrowDropDown, null, tint = c.quiet)
            }
            Box {
                IconButton(onClick = { choosing = true }) { Icon(ui.view.icon, stringResource(R.string.choose_view)) }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    CalendarView.entries.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(viewName(v)) },
                            leadingIcon = { Icon(v.icon, null) },
                            trailingIcon = { if (v == ui.view) Icon(Icons.Rounded.Check, null, tint = c.accent) },
                            onClick = {
                                choosing = false
                                actions.setView(v)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = actions::openSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.search)) }
            IconButton(onClick = { actions.goTo(ui.today) }) { Icon(Icons.Rounded.Today, stringResource(R.string.go_today)) }
            IconButton(onClick = actions::openSettings) {
                Box {
                    Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title))
                    if (ui.attention) Box(Modifier.size(9.dp).clip(CircleShape).background(c.danger).align(Alignment.TopEnd))
                }
            }
        }
        // Pinned above the days, so they stay in sight wherever the list is.
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ui.attention) Notice(Icons.Rounded.ErrorOutline, c.danger, stringResource(R.string.agenda_attention), actions::openSettings)
            if (ui.conflicts.isNotEmpty()) {
                Notice(
                    Icons.AutoMirrored.Rounded.MergeType,
                    c.caution,
                    pluralStringResource(R.plurals.agenda_conflicts, ui.conflicts.size, ui.conflicts.size),
                ) { showConflicts = true }
            }
        }
        PullToRefreshBox(isRefreshing = ui.syncing, onRefresh = actions::refresh, modifier = Modifier.fillMaxWidth().weight(1f)) {
            // A quick crossfade between views and out of "Loading" (decision 37).
            val shown = when {
                ui.loading -> null
                !ui.hasCalendars && ui.view != CalendarView.AGENDA -> CalendarView.AGENDA to false
                else -> ui.view to true
            }
            Crossfade(targetState = shown, animationSpec = tween(150), label = "view") { s ->
                when {
                    s == null -> Quiet(stringResource(R.string.agenda_loading), Modifier.padding(horizontal = 16.dp))
                    !s.second -> Quiet(stringResource(R.string.agenda_empty), Modifier.padding(horizontal = 16.dp))
                    else -> when (s.first) {
                        CalendarView.AGENDA -> AgendaList(ui, actions, f)
                        CalendarView.DAY, CalendarView.WEEK -> HoursPager(ui, actions, f, s.first)
                        CalendarView.MONTH -> MonthPager(ui, actions, f)
                        CalendarView.MONTH_DAY -> MonthDayView(ui, actions, f)
                    }
                }
            }
        }
        if (!ui.loading) Legend(ui.legend, ui.dimmed, actions::toggleCalendar)
    }
    // Above the legend.
    val above = if (ui.legend.isNotEmpty() && !ui.loading) 44.dp else 0.dp
    Column(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + above), horizontalAlignment = Alignment.End) {
        if (ui.writable.isNotEmpty() && !ui.loading) {
            FloatingActionButton(
                onClick = { actions.newEvent(null) },
                containerColor = c.accent,
                contentColor = c.onAccent,
            ) { Icon(Icons.Rounded.Add, stringResource(R.string.new_event)) }
        }
    }
    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 80.dp + above))
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = ui.anchor.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    state.selectedDateMillis?.let { actions.goTo(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text(stringResource(R.string.go)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    if (showConflicts && ui.conflicts.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showConflicts = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.canvas,
        ) {
            ConflictsContent(ui.conflicts, actions::dismiss)
        }
    }
    ui.day?.let { day ->
        ModalBottomSheet(
            onDismissRequest = actions::closeDay,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.canvas,
        ) {
            DayListContent(ui.byDate[day] ?: AgendaDay(day, emptyList()), ui.today, f, actions)
        }
    }
    ui.sheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = actions::closeSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface,
        ) {
            EventSheetContent(sheet, ui.today, f, onEdit = actions::edit, onDelete = actions::delete) { actions.dismiss(it) }
        }
    }
    ui.search?.let { s -> SearchDialog(s, ui.today, f, actions) }
    ui.editor?.let { ed -> EditorDialog(ed, ui.writable, f, actions.editor) }
    when (val ask = ui.ask) {
        is Ask.Delete -> ScopeDialog(delete = true, onAnswer = actions::answer)
        Ask.Save -> ScopeDialog(delete = false, onAnswer = actions::answer)
        null -> Unit
    }
}

/** The agenda (decision 24): days with their events, more loaded at either end. */
@Composable
private fun AgendaList(ui: CalendarUi, actions: CalendarActions, f: Formats) {
    val list = rememberLazyListState()
    // -1 until placed: it opens on the date in sight (today, or where it was before a rotation).
    var handled by remember { mutableIntStateOf(-1) }
    val current by rememberUpdatedState(ui)

    fun indexOf(day: LocalDate): Int? = current.days.indexOfFirst { it.date >= day }.takeIf { it >= 0 }

    LaunchedEffect(ui.jump, ui.from, ui.to, ui.days.size) {
        val target = if (handled < 0) ui.anchor else ui.jump.date
        if (handled == ui.jump.seq && handled >= 0) return@LaunchedEffect
        if (target < ui.from || target >= ui.to) return@LaunchedEffect
        val i = indexOf(target) ?: return@LaunchedEffect
        if (handled < 0) list.scrollToItem(i) else list.animateScrollToItem(i)
        handled = ui.jump.seq
    }
    // The top day is the date in sight; near either end, load more (prepended days keep the position, by key).
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo }
            .map { info -> Triple(info.visibleItemsInfo.firstOrNull()?.index ?: 0, info.visibleItemsInfo.lastOrNull()?.index ?: 0, info.totalItemsCount) }
            .distinctUntilChanged()
            .filter { handled >= 0 && current.days.isNotEmpty() }
            .collect { (top, bottom, total) ->
                current.days.getOrNull(list.firstVisibleItemIndex)?.let { actions.seen(it.date) }
                if (top <= 1) actions.loadEarlier()
                if (bottom >= total - 3) actions.loadLater()
            }
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        // Room at the end for the "+" button.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!ui.hasCalendars) item(key = "empty") { Quiet(stringResource(R.string.agenda_empty)) }
        items(ui.days, key = { "d-${it.date}" }) { day ->
            Box(Modifier.animateItem(fadeInSpec = tween(150), placementSpec = null, fadeOutSpec = tween(100))) {
                DayBlock(day, ui.today, f, actions)
            }
        }
    }
}

/** A day's list: from the month, or a "+2" in a crowded row. */
@Composable
fun DayListContent(day: AgendaDay, today: LocalDate, f: Formats, actions: CalendarActions) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayTitle(day.date, today, f), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.dayView(day.date) }) { Text(stringResource(R.string.view_day)) }
        }
        if (day.entries.isEmpty()) Quiet(stringResource(R.string.agenda_nothing))
        day.entries.forEach { EntryRow(it, f) { actions.open(it.occurrence) } }
    }
}

@Composable
internal fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Cz.colors.quiet, modifier = modifier.padding(horizontal = 4.dp, vertical = 16.dp))
}

/** A one-line notice above the days; tapping it goes to what it is about. */
@Composable
private fun Notice(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, text: String, onOpen: () -> Unit) {
    val c = Cz.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = tint)
        Text(text, modifier = Modifier.weight(1f))
    }
}

/** Every calendar conflict, with "Got it" (the sheet behind the notice). */
@Composable
fun ConflictsContent(conflicts: List<ConflictRow>, onDismiss: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.conflicts_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.conflicts_hint), color = Cz.colors.quiet, style = MaterialTheme.typography.bodySmall)
        conflicts.forEach { row -> ConflictCard(row) { onDismiss(row.id) } }
    }
}

@Composable
internal fun DayBlock(day: AgendaDay, today: LocalDate, f: Formats, actions: CalendarActions) {
    val c = Cz.colors
    val isToday = day.date == today
    // Days gone by step back.
    Column(Modifier.fillMaxWidth().alpha(if (day.date < today) 0.55f else 1f).padding(top = 14.dp)) {
        Text(
            dayTitle(day.date, today, f),
            style = MaterialTheme.typography.titleSmall,
            color = if (isToday) c.accent else c.quiet,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        if (day.entries.isEmpty()) {
            Text(stringResource(R.string.agenda_nothing), color = c.quiet, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            day.entries.forEach { EntryRow(it, f) { actions.open(it.occurrence) } }
        }
    }
}

@Composable
internal fun EntryRow(e: AgendaEntry, f: Formats, onClick: () -> Unit) {
    val c = Cz.colors
    val o = e.occurrence
    val color = parseColor(o.color) ?: c.accent
    val (first, second) = when (val t = e.time) {
        DayTime.AllDay -> stringResource(R.string.all_day) to null
        is DayTime.Range -> f.time(t.start) to t.end?.let(f::time)
        is DayTime.Starts -> f.time(t.start) to null
        is DayTime.Ends -> stringResource(R.string.until_label) to f.time(t.end)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().heightIn(min = 52.dp).background(color))
        Column(Modifier.width(76.dp).padding(start = 10.dp)) {
            Text(first, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            second?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.quiet, maxLines = 1) }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 8.dp)) {
            Text(
                o.title.ifBlank { stringResource(R.string.untitled) },
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val place = o.location?.takeIf { it.isNotBlank() }
            val meta = listOfNotNull(place, o.calendar.takeIf { !o.own }).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, color = c.quiet, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (o.pending) {
            val waiting = stringResource(R.string.event_pending)
            Icon(Icons.Rounded.CloudUpload, null, tint = c.quiet, modifier = Modifier.size(16.dp).semantics { contentDescription = waiting })
            Spacer(Modifier.width(6.dp))
        }
        if (!o.own) Initials(o.initials, color)
        Spacer(Modifier.width(10.dp))
    }
}

/** Whose calendar it is, as on the web: the owner's initials in the calendar's color. */
@Composable
private fun Initials(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** The event sheet, read-only for now (editing is step 4). */
@Composable
fun EventSheetContent(
    sheet: Sheet,
    today: LocalDate,
    f: Formats,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDismissConflict: (Long) -> Unit,
) {
    val c = Cz.colors
    val e = sheet.event
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (e == null) {
            Text(stringResource(R.string.event_gone), color = c.quiet)
            return@Column
        }
        val color = parseColor(e.color) ?: c.accent
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(top = 8.dp).size(14.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Text(e.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleLarge)
        }
        Field(stringResource(R.string.field_time), whenText(e, today, f))
        repeatText(e.repeat, e.repeatUntil, e.repeatCount, today, f)?.let { Field(stringResource(R.string.field_repeat), it) }
        e.location.takeIf { it.isNotBlank() }?.let { Field(stringResource(R.string.field_place), it) }
        e.description.takeIf { it.isNotBlank() }?.let { Field(stringResource(R.string.field_notes), it) }
        if (e.alarms.isNotEmpty()) {
            Field(stringResource(R.string.reminders_title), e.alarms.sorted().map { reminderText(it) }.joinToString("\n"))
        }
        val calendar = buildList {
            add(e.calendar)
            if (!e.own && e.owner.isNotBlank()) add(stringResource(R.string.shared_by, e.owner))
            if (!e.editable) add(stringResource(R.string.badge_view_only))
        }.joinToString(" · ")
        Field(stringResource(R.string.field_calendar), calendar)
        if (e.pending) Text(stringResource(R.string.event_pending), color = c.quiet, style = MaterialTheme.typography.bodySmall)
        // Edit, or only my reminders on a calendar I can view; Delete without "are you sure" (Undo instead).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onEdit, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (e.editable) R.string.edit else R.string.my_reminders))
            }
            if (e.editable) {
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = c.danger)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete), color = c.danger)
                }
            }
        }
        sheet.conflicts.forEach { row ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.conflicts_title), fontWeight = FontWeight.SemiBold)
                row.lines.forEach { Text("• " + explanationText(it)) }
                Text(
                    stringResource(R.string.conflict_dismiss),
                    color = c.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.End).clickable { onDismissConflict(row.id) }.padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, color = Cz.colors.quiet, style = MaterialTheme.typography.labelMedium)
        Text(value)
    }
}

/** "Wednesday 7 January, 16:00 to 17:00", or the days of an all-day event. */
@Composable
private fun whenText(e: EventView, today: LocalDate, f: Formats): String {
    val sd = runCatching { LocalDate.parse(e.startDate) }.getOrDefault(today)
    val ed = runCatching { LocalDate.parse(e.endDate) }.getOrDefault(sd)
    val st = e.startTime.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
    val et = e.endTime.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
    return when {
        e.allDay && ed <= sd -> f.weekday(sd, today)
        e.allDay -> stringResource(R.string.range, f.weekday(sd, today), f.weekday(ed, today))
        st == null -> f.weekday(sd, today)
        ed == sd -> stringResource(R.string.day_with_times, f.weekday(sd, today), f.time(st), et?.let(f::time) ?: f.time(st))
        else -> stringResource(
            R.string.range,
            stringResource(R.string.day_with_time, f.weekday(sd, today), f.time(st)),
            stringResource(R.string.day_with_time, f.weekday(ed, today), et?.let(f::time) ?: ""),
        )
    }
}
