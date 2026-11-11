package com.xblackbytesx.calzone.calendar

import androidx.compose.foundation.ScrollState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.model.Occurrence
import com.xblackbytesx.calzone.ui.Cz
import com.xblackbytesx.calzone.ui.parseColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.LocalTime
import kotlin.math.abs

/** An hour in the day and week views. */
private val HOUR = 56.dp

/** The hours' labels on the left. */
private val GUTTER = 48.dp

/** A bar in the all-day row and the month, and the room it takes. */
private val BAR = 18.dp
private val BAR_STEP = 20.dp

/** The month's day numbers above the bars. */
private val NUMBER = 26.dp

/** Black or white on a calendar's color, whichever reads better (the web's `textOn`). */
fun textOn(c: Color): Color {
    val l = c.luminance()
    val onWhite = 1.05f / (l + 0.05f)
    val onBlack = (l + 0.05f) / 0.05f
    return if (onBlack >= onWhite) Color.Black else Color.White
}

/** The person's own color for an occurrence's calendar. */
@Composable
private fun colorOf(o: Occurrence): Color = parseColor(o.color) ?: Cz.colors.accent

/**
 * Pages that follow the date in sight both ways: a swipe that settles tells
 * the view model, and a jump (Today, the date picker) moves the pager. The
 * jump seen when the pager is made is not taken again, so a rotation stays
 * where it was.
 */
@Composable
private fun rememberPages(ui: CalendarUi, view: CalendarView, actions: CalendarActions, f: Formats): PagerState {
    val first = f.firstDay
    val count = remember(view, first) { Pages.count(view, first) }
    val pager = rememberPagerState(initialPage = Pages.of(view, ui.anchor, first)) { count }
    val anchor by rememberUpdatedState(ui.anchor)
    val today by rememberUpdatedState(ui.today)
    var handled by rememberSaveable { mutableIntStateOf(ui.jump.seq) }
    LaunchedEffect(pager, view, first) {
        snapshotFlow { pager.settledPage }.collect { p ->
            if (p == Pages.of(view, anchor, first)) return@collect
            val d = Pages.date(view, p, anchor, first)
            // Month and days: a swiped-to month shows from today in this month, else from its 1st.
            val shown = if (view != CalendarView.MONTH_DAY) d else if (YearMonth.from(d) == YearMonth.from(today)) today else d.withDayOfMonth(1)
            actions.show(shown)
        }
    }
    LaunchedEffect(ui.jump) {
        if (ui.jump.seq == handled) return@LaunchedEffect
        handled = ui.jump.seq
        val p = Pages.of(view, ui.jump.date, first)
        if (abs(p - pager.currentPage) <= 3) pager.animateScrollToPage(p) else pager.scrollToPage(p)
    }
    return pager
}

/** The day and week views: the hours, side by side where events overlap, swiped a day or a week at a time. */
@Composable
internal fun HoursPager(ui: CalendarUi, actions: CalendarActions, f: Formats, view: CalendarView = ui.view) {
    val pager = rememberPages(ui, view, actions, f)
    val density = LocalDensity.current
    // One scroll for every page, so a swipe keeps the hour in sight.
    val inSight = if (view == CalendarView.DAY) ui.anchor == ui.today else ui.today in weekOf(ui.anchor, f.firstDay)
    val opening = with(density) { (HOUR * openingMinute(inSight, ui.now) / 60f).roundToPx() }
    val hours = rememberScrollState(opening)
    HorizontalPager(pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
        val date = Pages.date(view, page, ui.anchor, f.firstDay)
        val days = if (view == CalendarView.DAY) listOf(date) else weekOf(date, f.firstDay)
        HoursPage(days, ui, actions, f, hours)
    }
}

@Composable
private fun HoursPage(days: List<LocalDate>, ui: CalendarUi, actions: CalendarActions, f: Formats, hours: ScrollState) {
    val c = Cz.colors
    val wide = days.size == 1
    // Only the occurrences on these days (decision 37), not every loaded one.
    val pieces = remember(ui.byDate, days) { days.map { timedPieces(itemsOn(ui.byDate, listOf(it)), it) } }
    val bars = remember(ui.byDate, days) { spans(itemsOn(ui.byDate, days), days, timed = false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = GUTTER, top = 4.dp, bottom = 4.dp)) {
            days.forEach { d ->
                DayHead(d, ui.today, f, Modifier.weight(1f), onClick = if (wide) null else ({ actions.dayView(d) }))
            }
        }
        if (bars.isNotEmpty()) {
            Bars(
                bars,
                days.size,
                lanes = 3,
                onOpen = actions::open,
                onMore = { actions.openDay(days[it]) },
                modifier = Modifier.padding(start = GUTTER, bottom = 4.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Box(Modifier.fillMaxSize().verticalScroll(hours)) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(HOUR * 24).pointerInput(days) {
                    // A long press on an hour: a new event there, on the half hour.
                    detectTapGestures(onLongPress = { at ->
                        val left = GUTTER.toPx()
                        if (at.x < left) return@detectTapGestures
                        val i = ((at.x - left) / ((size.width - left) / days.size)).toInt().coerceIn(0, days.size - 1)
                        val minute = ((at.y / HOUR.toPx()) * 60).toInt().coerceIn(0, 23 * 60 + 30) / 30 * 30
                        actions.newEvent(days[i].atTime(minute / 60, minute % 60))
                    })
                },
            ) {
                val colW = (maxWidth - GUTTER) / days.size
                val line = c.line
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val left = GUTTER.toPx()
                        for (h in 1 until 24) {
                            val y = (HOUR * h).toPx()
                            drawLine(line, Offset(left, y), Offset(size.width, y), 1f)
                        }
                        for (i in 1 until days.size) {
                            val x = left + (colW * i).toPx()
                            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
                        }
                    },
                )
                for (h in 1 until 24) {
                    Text(
                        f.hour(h),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.quiet,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.offset(y = HOUR * h - 8.dp).width(GUTTER - 6.dp),
                    )
                }
                days.forEachIndexed { i, _ ->
                    pieces[i].forEach { p ->
                        val w = colW / p.cols
                        val height = maxOf(HOUR * (p.end - p.start) / 60f, 22.dp)
                        Block(
                            p,
                            wide,
                            f,
                            Modifier.offset(x = GUTTER + colW * i + w * p.col + 1.dp, y = HOUR * p.start / 60f + 1.dp)
                                .width(w - 2.dp)
                                .height(height - 2.dp),
                        ) { actions.open(p.occurrence) }
                    }
                }
                val ti = days.indexOf(ui.today)
                if (ti >= 0) {
                    val y = HOUR * (ui.now.toSecondOfDay() / 60) / 60f
                    Box(Modifier.offset(x = GUTTER + colW * ti, y = y - 1.dp).width(colW).height(2.dp).background(c.danger))
                    Box(Modifier.offset(x = GUTTER + colW * ti - 5.dp, y = y - 5.dp).size(10.dp).clip(CircleShape).background(c.danger))
                }
            }
        }
    }
}

/** A day's heading over its column: "Wed" and the date, today in the accent. */
@Composable
private fun DayHead(d: LocalDate, today: LocalDate, f: Formats, modifier: Modifier, onClick: (() -> Unit)?) {
    val c = Cz.colors
    val isToday = d == today
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(f.weekdayShort(d.dayOfWeek), style = MaterialTheme.typography.labelSmall, color = if (isToday) c.accent else c.quiet, maxLines = 1)
        DayNumber(d.dayOfMonth, isToday, quiet = false, big = true)
    }
}

@Composable
private fun DayNumber(n: Int, today: Boolean, quiet: Boolean, big: Boolean) {
    val c = Cz.colors
    val size = if (big) 30.dp else 22.dp
    Box(
        Modifier.size(size).clip(CircleShape).background(if (today) c.accent else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            n.toString(),
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
            color = when {
                today -> c.onAccent
                quiet -> c.quiet
                else -> c.text
            },
        )
    }
}

/** A timed event in the hours: its title, and in the day view its time and place. */
@Composable
private fun Block(p: Piece, wide: Boolean, f: Formats, modifier: Modifier, onClick: () -> Unit) {
    val o = p.occurrence
    val color = colorOf(o)
    val on = textOn(color)
    val title = o.title.ifBlank { stringResource(R.string.untitled) }
    // A week's columns are narrow: a smaller title, cut off rather than "…", which would take what room there is.
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(color).clickable(onClick = onClick).padding(horizontal = if (wide) 4.dp else 2.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                title,
                style = if (wide) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = on,
                overflow = if (wide) TextOverflow.Ellipsis else TextOverflow.Clip,
                maxLines = if (wide) 1 else 6,
                modifier = Modifier.weight(1f),
            )
            if (wide && !o.own) Text(o.initials, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = on)
        }
        if (wide) {
            val time = stringResource(R.string.range, f.time(LocalTime.ofSecondOfDay(p.start * 60L)), f.time(LocalTime.ofSecondOfDay((p.end % 1440) * 60L)))
            val meta = listOfNotNull(time, o.location?.takeIf { it.isNotBlank() }).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall, color = on.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A row of bars over `days` columns: as many lanes as `lanes` allows. When
 * there are more, the last lane shows, per day, the one event left or "+n",
 * which opens that day's list.
 */
@Composable
private fun Bars(
    spans: List<Span>,
    days: Int,
    lanes: Int,
    onOpen: ((Occurrence) -> Unit)?,
    onMore: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val used = (spans.maxOfOrNull { it.lane } ?: -1) + 1
    val room = lanes.coerceAtLeast(1)
    val fits = used <= room
    val full = if (fits) used else room - 1
    BoxWithConstraints(modifier.fillMaxWidth().height(BAR_STEP * minOf(used, room))) {
        val colW = maxWidth / days
        fun at(first: Int, last: Int, lane: Int) =
            Modifier.offset(x = colW * first + 1.dp, y = BAR_STEP * lane).width(colW * (last - first + 1) - 2.dp).height(BAR)
        spans.filter { it.lane < full }.forEach { s -> BarItem(s.occurrence, s.bar, at(s.first, s.last, s.lane), onOpen) }
        if (!fits) {
            for (i in 0 until days) {
                val hidden = spans.filter { it.lane >= full && i in it.first..it.last }
                when (hidden.size) {
                    0 -> Unit
                    1 -> BarItem(hidden[0].occurrence, hidden[0].bar, at(i, i, full), onOpen)
                    else -> Text(
                        "+${hidden.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Cz.colors.quiet,
                        textAlign = TextAlign.Center,
                        modifier = at(i, i, full).clip(RoundedCornerShape(4.dp)).clickable { onMore(i) },
                    )
                }
            }
        }
    }
}

/** An all-day or long event as a filled bar; a timed one (in the month) as a dot and its title. */
@Composable
private fun BarItem(o: Occurrence, bar: Boolean, modifier: Modifier, onOpen: ((Occurrence) -> Unit)?) {
    val color = colorOf(o)
    val title = o.title.ifBlank { stringResource(R.string.untitled) }
    val tap = if (onOpen != null) Modifier.clickable { onOpen(o) } else Modifier
    if (bar) {
        Box(modifier.clip(RoundedCornerShape(4.dp)).background(color).then(tap).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = textOn(color), maxLines = 1, overflow = TextOverflow.Clip)
        }
    } else {
        Row(modifier.clip(RoundedCornerShape(4.dp)).then(tap).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(3.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

/** The month: its weeks, each day with its bars; a day opens its list. */
@Composable
internal fun MonthPager(ui: CalendarUi, actions: CalendarActions, f: Formats) {
    val c = Cz.colors
    val pager = rememberPages(ui, CalendarView.MONTH, actions, f)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            weekOf(ui.today, f.firstDay).forEach { d ->
                Text(
                    f.weekdayShort(d.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.quiet,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalPager(pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
            val month = Pages.date(CalendarView.MONTH, page, ui.anchor, f.firstDay)
            val weeks = remember(month.year, month.monthValue, f.firstDay) { monthWeeks(month, f.firstDay) }
            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                weeks.forEach { week -> WeekRow(week, month, ui, actions, f, Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekRow(week: List<LocalDate>, month: LocalDate, ui: CalendarUi, actions: CalendarActions, f: Formats, modifier: Modifier) {
    val c = Cz.colors
    val bars = remember(ui.byDate, week) { spans(itemsOn(ui.byDate, week), week, timed = true) }
    val line = c.line
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxSize()) {
            week.forEach { d ->
                val label = f.weekday(d, ui.today)
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1f) }
                        .combinedClickable(onLongClick = { actions.newEvent(newStart(d, ui.today, ui.now)) }) { actions.openDay(d) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.padding(top = 2.dp)) {
                        DayNumber(d.dayOfMonth, d == ui.today, quiet = d.month != month.month, big = false)
                    }
                }
            }
        }
        val lanes = ((maxHeight - NUMBER) / BAR_STEP).toInt()
        Bars(
            bars,
            7,
            lanes,
            onOpen = null,
            onMore = { actions.openDay(week[it]) },
            modifier = Modifier.padding(top = NUMBER),
        )
    }
}

/** A day in the small month. */
private val SMALL_ROW = 46.dp

/**
 * Month and days (decision 33), as Fossify has it: a small month with a dot
 * per calendar on each day, swiped a month at a time, over the list of that
 * month's days. Tapping a day selects it and brings it to the top of the
 * list; a long press makes an event on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MonthDayView(ui: CalendarUi, actions: CalendarActions, f: Formats) {
    val c = Cz.colors
    val pager = rememberPages(ui, CalendarView.MONTH_DAY, actions, f)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            weekOf(ui.today, f.firstDay).forEach { d ->
                Text(
                    f.weekdayShort(d.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (d.dayOfWeek == ui.today.dayOfWeek) c.accent else c.quiet,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth().height(SMALL_ROW * 6), beyondViewportPageCount = 1, key = { it }) { page ->
            val m = Pages.date(CalendarView.MONTH_DAY, page, ui.anchor, f.firstDay)
            // Always six weeks, as Fossify has it, so the list below never jumps.
            val weeks = remember(m.year, m.monthValue, f.firstDay) {
                monthWeeks(m, f.firstDay).let { w -> w + (1..(6 - w.size)).map { n -> w.last().map { it.plusWeeks(n.toLong()) } } }
            }
            val dots = remember(ui.byDate, weeks) { dayColorsOf(ui.byDate, weeks.flatten()) }
            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                weeks.forEach { week ->
                    Row(Modifier.fillMaxWidth().height(SMALL_ROW)) {
                        week.forEach { d ->
                            SmallDay(d, m, ui, dots[d].orEmpty(), f, actions, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        // Another month: its list slides in from the side it was swiped from, already at its day (no scroll
        // through the list after the swipe). Short, so it never holds anything up (decision 37).
        AnimatedContent(
            targetState = YearMonth.from(ui.anchor),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(180)) { w -> dir * w / 6 } + fadeIn(tween(180))) togetherWith fadeOut(tween(90))
            },
            modifier = Modifier.fillMaxSize(),
            label = "month list",
        ) { month ->
            MonthList(month, ui, actions, f)
        }
    }
}

/** One month's days under the small month; a day tapped in it scrolls up to the top. */
@Composable
private fun MonthList(month: YearMonth, ui: CalendarUi, actions: CalendarActions, f: Formats) {
    val days = remember(ui.byDate, month, ui.today) { monthDaysOf(ui.byDate, month.atDay(1), ui.today) }
    fun indexOf(d: LocalDate) = days.indexOfFirst { it.date >= d }.takeIf { it >= 0 } ?: (days.size - 1).coerceAtLeast(0)
    val start = if (YearMonth.from(ui.anchor) == month) ui.anchor else month.atDay(1)
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = indexOf(start))
    val anchor = ui.anchor
    var placed by remember { mutableStateOf(anchor) }
    LaunchedEffect(anchor, days) {
        if (YearMonth.from(anchor) != month || days.isEmpty()) return@LaunchedEffect
        if (anchor != placed) list.animateScrollToItem(indexOf(anchor)) else list.scrollToItem(indexOf(anchor))
        placed = anchor
    }
    androidx.compose.foundation.lazy.LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
    ) {
        if (days.isEmpty()) item(key = "empty") { Quiet(stringResource(R.string.month_nothing)) }
        items(days.size, key = { "d-${days[it].date}" }) { i ->
            Box(Modifier.animateItem(fadeInSpec = tween(150), placementSpec = tween(150), fadeOutSpec = tween(100))) {
                DayBlock(days[i], ui.today, f, actions)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmallDay(d: LocalDate, month: LocalDate, ui: CalendarUi, dots: List<String>, f: Formats, actions: CalendarActions, modifier: Modifier) {
    val c = Cz.colors
    val selected = d == ui.anchor && d != ui.today
    val ring by animateColorAsState(if (selected) c.accent else Color.Transparent, tween(120), label = "selected")
    val label = f.weekday(d, ui.today)
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp))
            // A grayed day of the month before or after turns the page there too.
            .combinedClickable(onLongClick = { actions.newEvent(newStart(d, ui.today, ui.now)) }) {
                if (d.month != month.month) actions.goTo(d) else actions.show(d)
            }
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(top = 4.dp).size(30.dp).clip(CircleShape)
                .background(if (d == ui.today) c.accent else Color.Transparent)
                .border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (d == ui.today) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    d == ui.today -> c.onAccent
                    d.month != month.month -> c.quiet
                    else -> c.text
                },
            )
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            dots.forEach { hex -> Box(Modifier.size(5.dp).clip(CircleShape).background(parseColor(hex) ?: c.accent)) }
        }
    }
}

/**
 * Which calendar is which color (decision 33), along the bottom as Fossify
 * has it: the calendars this phone shows, each name over a bar in its color.
 */
@Composable
internal fun Legend(calendars: List<CalendarChoice>, modifier: Modifier = Modifier) {
    if (calendars.isEmpty()) return
    val c = Cz.colors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val each = maxOf(maxWidth / calendars.size, 96.dp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            calendars.forEach { cal ->
                Column(Modifier.width(each).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        cal.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(4.dp).background(parseColor(cal.color) ?: c.accent))
                }
            }
        }
    }
}
