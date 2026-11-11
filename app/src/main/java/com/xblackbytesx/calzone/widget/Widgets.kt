package com.xblackbytesx.calzone.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.xblackbytesx.calzone.MainActivity
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.calendar.Intents
import com.xblackbytesx.calzone.ui.DarkCz
import com.xblackbytesx.calzone.ui.LightCz
import com.xblackbytesx.calzone.ui.parseColor
import java.time.LocalDate

/** The app's colors, light or dark with the phone (widgets follow the system, decision 35). */
private object W {
    val canvas = ColorProvider(day = LightCz.canvas, night = DarkCz.canvas)
    val surface = ColorProvider(day = LightCz.surface, night = DarkCz.surface)
    val text = ColorProvider(day = LightCz.text, night = DarkCz.text)
    val quiet = ColorProvider(day = LightCz.quiet, night = DarkCz.quiet)
    val accent = ColorProvider(day = LightCz.accent, night = DarkCz.accent)
    val onAccent = ColorProvider(day = LightCz.onAccent, night = DarkCz.onAccent)

    fun of(hex: String?): ColorProvider = (parseColor(hex) ?: Color(0xFF3A7BD5)).let { ColorProvider(day = it, night = it) }
}

private fun open(context: Context, action: String, key: String, fill: Intent.() -> Unit = {}): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(action)
        // Each target its own PendingIntent.
        .setData("calzone-widget://$action/${Uri.encode(key)}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply(fill)

private fun openDay(context: Context, d: LocalDate) = open(context, Intents.OPEN_DAY, d.toString()) { putExtra(Intents.EXTRA_DATE, d.toString()) }

@Composable
private fun Message(context: Context, s: WidgetState) {
    val text = when (s) {
        WidgetState.SignedOut -> context.getString(R.string.widget_signed_out)
        else -> context.getString(R.string.widget_failed)
    }
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(W.canvas).padding(16.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(color = W.quiet, fontSize = 14.sp, textAlign = TextAlign.Center))
    }
}

/** The next two weeks, as the agenda shows them (decision 35). */
class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val state = WidgetData.load(context, today, today.plusDays(14))
        val now = System.currentTimeMillis() / 1000
        val lines = (state as? WidgetState.Ready)?.let { WidgetData.agendaLines(context, it, now) }
        val title = WidgetData.formats(context).let { f -> f.weekday(today, today).replaceFirstChar { it.titlecase(f.locale) } }
        provideContent {
            if (lines == null) {
                Message(context, state)
                return@provideContent
            }
            Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(W.canvas).padding(12.dp)) {
                Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = TextStyle(color = W.text, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(openDay(context, today))),
                    )
                    Box(
                        GlanceModifier.size(32.dp).cornerRadius(16.dp).background(W.accent)
                            .clickable(actionStartActivity(open(context, Intents.NEW_EVENT, "new"))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            ImageProvider(R.drawable.ic_widget_add),
                            context.getString(R.string.new_event),
                            colorFilter = ColorFilter.tint(W.onAccent),
                            modifier = GlanceModifier.size(20.dp),
                        )
                    }
                }
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(lines) { line ->
                        when (line) {
                            is AgendaLine.Heading -> Text(
                                line.text,
                                style = TextStyle(color = if (line.today) W.accent else W.quiet, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                modifier = GlanceModifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                            )
                            is AgendaLine.Nothing -> Text(
                                line.text,
                                style = TextStyle(color = W.quiet, fontSize = 13.sp),
                                modifier = GlanceModifier.padding(start = 4.dp, bottom = 2.dp),
                            )
                            is AgendaLine.Entry -> EntryLine(context, line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryLine(context: Context, line: AgendaLine.Entry) {
    val o = line.o
    val target = open(context, Intents.OPEN_EVENT, "${o.collection}/${o.name}/${o.instance}") {
        putExtra(Intents.EXTRA_COLLECTION, o.collection)
        putExtra(Intents.EXTRA_NAME, o.name)
        putExtra(Intents.EXTRA_INSTANCE, o.instance)
    }
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            GlanceModifier.fillMaxWidth().cornerRadius(10.dp).background(W.surface).clickable(actionStartActivity(target)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.width(4.dp).height(40.dp).background(W.of(o.color))) {}
            Column(GlanceModifier.defaultWeight().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    o.title.ifBlank { context.getString(R.string.untitled) },
                    style = TextStyle(color = W.text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    listOfNotNull(line.time, o.location?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = TextStyle(color = W.quiet, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** This month with a dot per calendar on each day (decision 35); a day opens it in the app. */
class MonthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val f = WidgetData.formats(context)
        val today = LocalDate.now()
        val first = com.xblackbytesx.calzone.calendar.weekStart(today.withDayOfMonth(1), f.firstDay)
        val state = WidgetData.load(context, first, first.plusWeeks(6))
        val ready = state as? WidgetState.Ready
        val month = ready?.let { WidgetData.month(it, f.firstDay) }
        provideContent {
            if (ready == null || month == null) {
                Message(context, state)
                return@provideContent
            }
            val (weeks, dots) = month
            Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(W.canvas).padding(10.dp)) {
                Text(
                    f.month(today),
                    style = TextStyle(color = W.text, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.padding(start = 6.dp, bottom = 4.dp).clickable(actionStartActivity(openDay(context, today))),
                )
                Row(GlanceModifier.fillMaxWidth()) {
                    weeks.first().forEach { d ->
                        Text(
                            f.weekdayShort(d.dayOfWeek),
                            style = TextStyle(color = if (d.dayOfWeek == today.dayOfWeek) W.accent else W.quiet, fontSize = 11.sp, textAlign = TextAlign.Center),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                }
                weeks.forEach { week ->
                    Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                        week.forEach { d -> DayCell(context, d, today, dots[d].orEmpty()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.glance.layout.RowScope.DayCell(context: Context, d: LocalDate, today: LocalDate, dots: List<String>) {
    val isToday = d == today
    Column(
        GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(openDay(context, d))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier.size(24.dp).cornerRadius(12.dp).background(if (isToday) W.accent else W.canvas),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.dayOfMonth.toString(),
                style = TextStyle(
                    color = when {
                        isToday -> W.onAccent
                        d.month != today.month -> W.quiet
                        else -> W.text
                    },
                    fontSize = 12.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
        Row(GlanceModifier.height(6.dp), verticalAlignment = Alignment.CenterVertically) {
            dots.forEach { hex ->
                Box(GlanceModifier.size(5.dp).cornerRadius(3.dp).background(W.of(hex))) {}
                Spacer(GlanceModifier.width(2.dp))
            }
        }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()
}
