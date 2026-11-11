package com.xblackbytesx.calzone.calendar

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.xblackbytesx.calzone.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Dates and times as the phone says them: its language and its 12- or
 * 24-hour setting (decision 52 in calzone-server: the phone's settings, not
 * the web's).
 */
class Formats(val locale: Locale, val clock24: Boolean) {
    private fun pattern(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val time = DateTimeFormatter.ofPattern(if (clock24) "HH:mm" else "h:mm a", locale)
    private val weekdayDay = pattern("EEEEdMMMM")
    private val weekdayDayYear = pattern("EEEEdMMMMyyyy")
    private val day = pattern("dMMMM")
    private val dayYear = pattern("dMMMMyyyy")
    private val month = pattern("MMMMyyyy")
    private val hour = DateTimeFormatter.ofPattern(if (clock24) "HH:mm" else "h a", locale)

    /** The phone's first day of the week (its region, or its own choice in Android's settings). */
    val firstDay: DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    fun time(t: LocalTime): String = time.format(t)

    /** An hour on the grid's edge: "09:00" or "9 AM". */
    fun hour(h: Int): String = hour.format(LocalTime.of(h, 0))

    /** "Mon", "ma". */
    fun weekdayShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT_STANDALONE, locale).trimEnd('.')

    /** "Wednesday 7 January", with the year when it is not this year's. */
    fun weekday(d: LocalDate, today: LocalDate): String =
        (if (d.year == today.year) weekdayDay else weekdayDayYear).format(d)

    /** "7 January", with the year when it is not this year's. */
    fun date(d: LocalDate, today: LocalDate): String = (if (d.year == today.year) day else dayYear).format(d)

    /** "November 2026". */
    fun month(d: LocalDate): String = month.format(d).replaceFirstChar { it.titlecase(locale) }

    /** The calendar's title: "November" this year, "November 2027" another (the top bar has room for little). */
    fun monthTitle(d: LocalDate, today: LocalDate): String =
        if (d.year == today.year) d.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } else month(d)
}

@Composable
fun rememberFormats(): Formats {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val clock24 = DateFormat.is24HourFormat(context)
    return remember(locale, clock24) { Formats(locale, clock24) }
}

/** "Today", "Tomorrow", or the weekday and date. */
@Composable
fun dayTitle(d: LocalDate, today: LocalDate, f: Formats): String = when (d) {
    today -> stringResource(R.string.agenda_today)
    today.plusDays(1) -> stringResource(R.string.agenda_tomorrow)
    today.minusDays(1) -> stringResource(R.string.agenda_yesterday)
    // A heading starts with a capital, also in Dutch ("zaterdag" in a sentence).
    else -> f.weekday(d, today).replaceFirstChar { it.titlecase(f.locale) }
}

/** A reminder in words: "At the start", "15 minutes before", "1 day before". */
@Composable
fun reminderText(minutes: Long): String {
    val m = minutes.toInt()
    return when {
        m <= 0 -> stringResource(R.string.reminder_at_start)
        m % 1440 == 0 -> pluralStringResource(R.plurals.reminder_days, m / 1440, m / 1440)
        m % 60 == 0 -> pluralStringResource(R.plurals.reminder_hours, m / 60, m / 60)
        else -> pluralStringResource(R.plurals.reminder_minutes, m, m)
    }
}

/** How an event repeats, in words, with its end. */
@Composable
fun repeatText(repeat: String, until: String?, count: Int?, today: LocalDate, f: Formats): String? {
    val base = when (repeat) {
        "daily" -> stringResource(R.string.repeat_daily)
        "weekly" -> stringResource(R.string.repeat_weekly)
        "monthly" -> stringResource(R.string.repeat_monthly)
        "yearly" -> stringResource(R.string.repeat_yearly)
        "custom" -> return stringResource(R.string.repeat_custom)
        else -> return null
    }
    val untilDay = until?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return when {
        untilDay != null -> stringResource(R.string.repeat_until, base, f.date(untilDay, today))
        count != null -> pluralStringResource(R.plurals.repeat_times, count, base, count)
        else -> base
    }
}
