package com.xblackbytesx.calzone.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.calendar.ALARM_PRESETS
import com.xblackbytesx.calzone.calendar.reminderText
import com.xblackbytesx.calzone.calendar.textOn
import com.xblackbytesx.calzone.model.Mine
import com.xblackbytesx.calzone.ui.Cz
import com.xblackbytesx.calzone.ui.czSwitchColors
import com.xblackbytesx.calzone.ui.parseColor

/** The colors offered for my own view of a calendar (the web's palette). */
val PALETTE = listOf("#3a7bd5", "#c2185b", "#00897b", "#f4511e", "#7e57c2", "#43a047", "#8d6e63", "#1e88e5")

/**
 * My own view of one calendar (decision 31): my name and color for it,
 * hidden everywhere, and the reminder new events get. Only I see these; they
 * reach the web and my other phones with the next sync.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarPrefsContent(row: CollectionRow, onSave: (Mine) -> Unit) {
    val c = Cz.colors
    val mine = row.mine
    var name by rememberSaveable(row.id) { mutableStateOf(mine.name ?: "") }
    var color by rememberSaveable(row.id) { mutableStateOf(mine.color) }
    var hidden by rememberSaveable(row.id) { mutableStateOf(mine.hidden) }
    var alarm by rememberSaveable(row.id) { mutableStateOf(mine.defaultAlarm) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.calendar_prefs_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.calendar_prefs_hint), color = c.quiet, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            name,
            { name = it },
            label = { Text(stringResource(R.string.field_name)) },
            placeholder = { Text(row.baseName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.calendar_color), color = c.quiet, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The calendar's own color first: my color cleared.
            (listOf<String?>(null) + PALETTE.filter { it != row.baseColor }).forEach { hex ->
                val shown = parseColor(hex ?: row.baseColor) ?: c.accent
                val chosen = color == hex
                val label = if (hex == null) stringResource(R.string.calendar_color_own) else hex
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(shown)
                        .then(if (chosen) Modifier.border(3.dp, c.text, CircleShape) else Modifier)
                        .clickable { color = hex }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) Icon(Icons.Rounded.Check, null, tint = textOn(shown))
                }
            }
        }
        Text(stringResource(R.string.calendar_default_reminder), color = c.quiet, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf<Long?>(null) + ALARM_PRESETS).forEach { m ->
                FilterChip(
                    selected = alarm?.toLong() == m,
                    onClick = { alarm = m?.toInt() },
                    label = { Text(if (m == null) stringResource(R.string.reminder_none) else reminderText(m)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = c.onAccent),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.calendar_hide_everywhere))
                Text(stringResource(R.string.calendar_hide_hint), color = c.quiet, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = hidden,
                onCheckedChange = { hidden = it },
                colors = czSwitchColors(),
            )
        }
        Button(
            onClick = { onSave(mine.copy(name = name.trim().ifBlank { null }, color = color, hidden = hidden, defaultAlarm = alarm)) },
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.save)) }
    }
}
