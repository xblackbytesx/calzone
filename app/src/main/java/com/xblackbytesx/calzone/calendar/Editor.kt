package com.xblackbytesx.calzone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.ui.Cz
import com.xblackbytesx.calzone.ui.czSwitchColors
import com.xblackbytesx.calzone.ui.parseColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** A calendar a new event can go in (writable), with the person's default reminder for it. */
data class CalendarChoice(val id: String, val label: String, val color: String?, val defaultAlarm: Int?)

/** The event an edit is about: one occurrence (`instance`) of a recurring one, or the whole event. */
data class Target(val collection: String, val name: String, val instance: String?)

/**
 * The editor open: the form as it was and as it is now. `target` null is a
 * new event; `remindersOnly` is a calendar the person can only view (their
 * own reminders are still theirs).
 */
data class EditorState(
    val initial: Draft,
    val draft: Draft,
    val target: Target? = null,
    val recurring: Boolean = false,
    val remindersOnly: Boolean = false,
) {
    val isNew: Boolean get() = target == null
    val dirty: Boolean get() = draft != initial
}

interface EditorActions {
    fun change(d: Draft)

    /** Quick add: read the text and fill the form with it. */
    fun quick(text: String)
    fun save()
    fun close()
}

/** The editor over the whole screen; closing it with changes asks first (only typed work, never a delete). */
@Composable
fun EditorDialog(ed: EditorState, calendars: List<CalendarChoice>, f: Formats, actions: EditorActions) {
    var confirm by remember { mutableStateOf(false) }
    val tryClose = { if (ed.dirty) confirm = true else actions.close() }
    Dialog(
        onDismissRequest = tryClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        EditorContent(
            ed,
            calendars,
            f,
            object : EditorActions by actions {
                override fun close() = tryClose()
            },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.discard_q)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    actions.close()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
}

private enum class Pick { START_DATE, START_TIME, END_DATE, END_TIME, UNTIL }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorContent(ed: EditorState, calendars: List<CalendarChoice>, f: Formats, actions: EditorActions) {
    val c = Cz.colors
    val d = ed.draft
    var picking by remember { mutableStateOf<Pick?>(null) }
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().background(c.canvas).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::close) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
            Text(
                stringResource(
                    when {
                        ed.remindersOnly -> R.string.reminders_title
                        ed.isNew -> R.string.new_event
                        else -> R.string.edit_event
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = actions::save,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text(stringResource(R.string.save)) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ed.remindersOnly) {
                Text(d.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.read_only_hint), color = c.quiet)
            } else {
                if (ed.isNew) QuickAdd(actions::quick)
                OutlinedTextField(
                    d.title,
                    { actions.change(d.copy(title = it)) },
                    label = { Text(stringResource(R.string.field_title)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ed.isNew && calendars.size > 1) {
                    val chosen = calendars.firstOrNull { it.id == d.collection }
                    Choice(
                        stringResource(R.string.field_calendar),
                        chosen?.label ?: "",
                        calendars.map { it to it.label },
                        dot = { parseColor(it.color) ?: c.accent },
                    ) { cal ->
                        // A new calendar's default reminder, while the person has not chosen one.
                        val before = calendars.firstOrNull { it.id == d.collection }?.defaultAlarm
                        val untouched = d.alarms == listOfNotNull(before?.toLong())
                        actions.change(d.copy(collection = cal.id, alarms = if (untouched) listOfNotNull(cal.defaultAlarm?.toLong()) else d.alarms))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.all_day), modifier = Modifier.weight(1f))
                    Switch(
                        checked = d.allDay,
                        onCheckedChange = { actions.change(d.copy(allDay = it)) },
                        colors = czSwitchColors(),
                    )
                }
                When(stringResource(R.string.starts), f.date(d.startDate, today), if (d.allDay) null else f.time(d.start.toLocalTime()), { picking = Pick.START_DATE }, { picking = Pick.START_TIME })
                When(stringResource(R.string.ends), f.date(d.endDate, today), if (d.allDay) null else f.time(d.end.toLocalTime()), { picking = Pick.END_DATE }, { picking = Pick.END_TIME })
                val repeats = buildList {
                    add("none" to stringResource(R.string.repeat_none))
                    add("daily" to stringResource(R.string.repeat_daily))
                    add("weekly" to stringResource(R.string.repeat_weekly))
                    add("monthly" to stringResource(R.string.repeat_monthly))
                    add("yearly" to stringResource(R.string.repeat_yearly))
                    if (ed.initial.repeat == "custom") add("custom" to stringResource(R.string.repeat_custom))
                }
                Choice(stringResource(R.string.field_repeat), repeats.firstOrNull { it.first == d.repeat }?.second ?: "", repeats) {
                    actions.change(d.copy(repeat = it))
                }
                if (d.repeat in setOf("daily", "weekly", "monthly", "yearly")) {
                    Text(stringResource(R.string.repeat_stops), color = c.quiet, style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            RepeatEnd.NEVER to R.string.repeat_never,
                            RepeatEnd.UNTIL to R.string.repeat_on_day,
                            RepeatEnd.COUNT to R.string.repeat_after,
                        ).forEach { (end, label) ->
                            Chip(stringResource(label), d.repeatEnd == end) {
                                actions.change(
                                    d.copy(
                                        repeatEnd = end,
                                        until = if (end == RepeatEnd.UNTIL) d.until ?: d.startDate.plusMonths(3) else d.until,
                                        count = if (end == RepeatEnd.COUNT) d.count ?: 10 else d.count,
                                    ),
                                )
                            }
                        }
                    }
                    when (d.repeatEnd) {
                        RepeatEnd.UNTIL -> Pill(f.date(d.until ?: d.startDate, today)) { picking = Pick.UNTIL }
                        RepeatEnd.COUNT -> OutlinedTextField(
                            d.count?.toString() ?: "",
                            { s -> actions.change(d.copy(count = s.filter(Char::isDigit).take(4).toIntOrNull()?.coerceIn(1, 1000))) },
                            label = { Text(stringResource(R.string.repeat_times_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(180.dp),
                        )
                        RepeatEnd.NEVER -> Unit
                    }
                }
                OutlinedTextField(
                    d.location,
                    { actions.change(d.copy(location = it)) },
                    label = { Text(stringResource(R.string.field_place)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    d.notes,
                    { actions.change(d.copy(notes = it)) },
                    label = { Text(stringResource(R.string.field_notes)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.reminders_title), color = c.quiet, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (ALARM_PRESETS + d.alarms).distinct().sorted().forEach { m ->
                        val on = m in d.alarms
                        Chip(reminderText(m), on) { actions.change(d.copy(alarms = if (on) d.alarms - m else (d.alarms + m).sorted())) }
                    }
                }
                Text(stringResource(R.string.reminders_hint), color = c.quiet, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (picking) {
        Pick.START_DATE -> DateDialog(d.startDate, { picking = null }) { actions.change(d.withStart(it, d.startTime)) }
        Pick.END_DATE -> DateDialog(d.endDate, { picking = null }) { actions.change(d.withEnd(it, d.endTime)) }
        Pick.UNTIL -> DateDialog(d.until ?: d.startDate, { picking = null }) { actions.change(d.copy(until = if (it < d.startDate) d.startDate else it)) }
        Pick.START_TIME -> TimeDialog(d.start.toLocalTime(), f.clock24, { picking = null }) { actions.change(d.withStart(d.startDate, it)) }
        Pick.END_TIME -> TimeDialog(d.end.toLocalTime(), f.clock24, { picking = null }) { actions.change(d.withEnd(d.endDate, it)) }
        null -> Unit
    }
}

/** Quick add as on the web: "dentist thu 3pm" fills the form. */
@Composable
private fun QuickAdd(onFill: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val fill = stringResource(R.string.quick_fill)
    OutlinedTextField(
        text,
        { text = it },
        placeholder = { Text(stringResource(R.string.quick_add_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onFill(text) }),
        trailingIcon = {
            IconButton(onClick = { if (text.isNotBlank()) onFill(text) }) { Icon(Icons.Rounded.AutoAwesome, fill) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun When(label: String, date: String, time: String?, onDate: () -> Unit, onTime: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Cz.colors.quiet, modifier = Modifier.width(72.dp))
        Pill(date, onDate)
        time?.let { Pill(it, onTime) }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    val c = Cz.colors
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Cz.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = c.onAccent),
    )
}

/** A labeled value that opens a menu of choices. */
@Composable
private fun <T> Choice(label: String, current: String, options: List<Pair<T, String>>, dot: ((T) -> androidx.compose.ui.graphics.Color)? = null, onPick: (T) -> Unit) {
    val c = Cz.colors
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = c.quiet, style = MaterialTheme.typography.labelMedium)
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface).clickable { open = true }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dot?.let { options.firstOrNull { o -> o.second == current }?.let { o -> Dot(it(o.first)) } }
                Text(current, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Rounded.ArrowDropDown, null, tint = c.quiet)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        leadingIcon = dot?.let { { Dot(it(value)) } },
                        onClick = {
                            open = false
                            onPick(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.padding(end = 8.dp).size(12.dp).clip(CircleShape).background(color))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onDone: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDone,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDone()
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, clock24: Boolean, onDone: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = clock24)
    AlertDialog(
        onDismissRequest = onDone,
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDone()
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Change which ones?" for a recurring event, as on the web: only this one, this and following, all of them. */
@Composable
fun ScopeDialog(delete: Boolean, onAnswer: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(stringResource(if (delete) R.string.scope_delete else R.string.scope_edit)) },
        text = {
            Column {
                listOf("this" to R.string.scope_this, "following" to R.string.scope_following, "all" to R.string.scope_all).forEach { (scope, label) ->
                    Text(
                        stringResource(label),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onAnswer(scope) }.padding(vertical = 14.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

