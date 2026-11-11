package com.xblackbytesx.calzone.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.ui.Cz
import java.time.LocalDate

/** Search over full screen (decision 34). */
@Composable
fun SearchDialog(s: SearchUi, today: LocalDate, f: Formats, actions: CalendarActions) {
    Dialog(onDismissRequest = actions::closeSearch, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SearchContent(s, today, f, actions, focus = true)
    }
}

/** Title, place and notes, every word; coming events first, then past ones. Tapping one opens its sheet. */
@Composable
fun SearchContent(s: SearchUi, today: LocalDate, f: Formats, actions: CalendarActions, focus: Boolean = false) {
    val c = Cz.colors
    val focusRequester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Column(Modifier.fillMaxSize().background(c.canvas).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::closeSearch) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            OutlinedTextField(
                s.query,
                actions::search,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (s.query.isNotEmpty()) IconButton(onClick = { actions.search("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear)) }
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
        }
        val results = s.results
        when {
            s.query.isBlank() -> Quiet(stringResource(R.string.search_what), Modifier.padding(horizontal = 16.dp))
            results == null -> Quiet(stringResource(R.string.search_running), Modifier.padding(horizontal = 16.dp))
            results.isEmpty() -> Quiet(stringResource(R.string.search_none), Modifier.padding(horizontal = 16.dp))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(results, key = { i, o -> "$i/${o.collection}/${o.name}/${o.instance}" }) { i, o ->
                    val (date, time) = daysOf(o).firstOrNull() ?: return@itemsIndexed
                    // A heading where the day changes, as in the agenda; past results dimmed.
                    val prev = results.getOrNull(i - 1)?.let { daysOf(it).firstOrNull()?.first }
                    Column(Modifier.alpha(if (date < today) 0.55f else 1f)) {
                        if (prev != date) {
                            Text(
                                dayTitle(date, today, f),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (date == today) c.accent else c.quiet,
                                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
                            )
                        }
                        EntryRow(AgendaEntry(o, time), f) { actions.open(o) }
                    }
                }
            }
        }
    }
}
