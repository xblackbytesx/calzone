package com.xblackbytesx.calzone.home

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.account.AndroidAccounts
import com.xblackbytesx.calzone.account.Session
import com.xblackbytesx.calzone.bridge.Bridges
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.model.Account
import com.xblackbytesx.calzone.model.ConflictDetail
import com.xblackbytesx.calzone.prefs.Network
import com.xblackbytesx.calzone.prefs.Prefs
import com.xblackbytesx.calzone.prefs.PrefsStore
import com.xblackbytesx.calzone.prefs.Theme
import com.xblackbytesx.calzone.push.Push
import com.xblackbytesx.calzone.sync.Problem
import com.xblackbytesx.calzone.sync.SyncRunner
import com.xblackbytesx.calzone.sync.SyncStatus
import com.xblackbytesx.calzone.sync.SyncWorker
import com.xblackbytesx.calzone.ui.Cz
import com.xblackbytesx.calzone.ui.parseColor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Push as the person sees it. */
enum class PushState {
    /** The server has no push hosts (CALZONE_PUSH_HOSTS): nothing to switch on. */
    SERVER_OFF,

    /** No UnifiedPush distributor (ntfy) on this phone. */
    NO_DISTRIBUTOR,
    OFF,
    ON,
}

data class CollectionRow(
    val id: String,
    val label: String,
    val color: String?,
    /** Whose it is, when it is not the person's own. */
    val owner: String?,
    val readOnly: Boolean,
    val subscribed: Boolean,
    val count: Int,
    val shown: Boolean,
)

data class ConflictRow(
    val id: Long,
    /** The event's title or the contact's name, else the object's file name. */
    val title: String,
    val collection: String,
    val at: Long,
    val lines: List<Explanation>,
)

data class HomeUi(
    val account: Account? = null,
    val server: String? = null,
    val status: SyncStatus = SyncStatus(),
    val networkOk: Boolean = true,
    val calendars: List<CollectionRow> = emptyList(),
    val books: List<CollectionRow> = emptyList(),
    val pending: Int = 0,
    val conflicts: List<ConflictRow> = emptyList(),
    val push: PushState = PushState.OFF,
    val theme: Theme = Theme.DARK,
    /** The bridges may write Android's calendar and contacts (decisions 13 and 17). */
    val systemAccess: Boolean = true,
)

interface HomeActions {
    fun syncNow()
    fun setShown(id: String, show: Boolean)
    fun dismiss(id: Long)
    fun setPush(on: Boolean)
    fun setTheme(t: Theme)
    fun openNetworkSettings()
    fun allowAccess()
    fun signInAgain()
    fun signOut()
}

/** The phone's own state, read on every resume. */
private data class Device(val networkOk: Boolean = true, val push: PushState = PushState.OFF, val access: Boolean = true)

/** What the store holds, read in one go on the IO thread. */
private data class Loaded(
    val account: Account?,
    val server: String?,
    val collections: List<com.xblackbytesx.calzone.model.StoredCollection>,
    val counts: Map<String, Int>,
    val conflicts: List<ConflictRow>,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val core: Core,
    private val session: Session,
    private val runner: SyncRunner,
    private val prefsStore: PrefsStore,
    private val bridges: Bridges,
) : ViewModel() {
    private val loaded = MutableStateFlow<Loaded?>(null)
    private val device = MutableStateFlow(Device())
    private val _ui = MutableStateFlow(HomeUi())
    val ui: StateFlow<HomeUi> = _ui

    init {
        viewModelScope.launch {
            combine(loaded, runner.status, prefsStore.prefs, device) { l, s, p, d -> build(l, s, p, d) }.collect { _ui.value = it }
        }
        // Reload the store after every run (and once now).
        viewModelScope.launch {
            runner.status.map { it.lastOk to it.running }.distinctUntilChanged().collect { reload() }
        }
    }

    private fun build(l: Loaded?, s: SyncStatus, p: Prefs, d: Device): HomeUi {
        fun row(sc: com.xblackbytesx.calzone.model.StoredCollection): CollectionRow {
            val c = sc.info
            return CollectionRow(
                id = c.id,
                label = c.label,
                color = c.shownColor,
                owner = if (c.isMine) null else c.ownerName,
                readOnly = c.readOnly,
                subscribed = c.subscribed,
                count = l?.counts?.get(c.id) ?: 0,
                shown = p.shows(c.id, c.mine.hidden),
            )
        }
        val cols = l?.collections.orEmpty()
        val push = when {
            l?.account?.pushHosts.isNullOrEmpty() -> PushState.SERVER_OFF
            else -> d.push
        }
        return HomeUi(
            account = l?.account,
            server = l?.server,
            status = s,
            networkOk = d.networkOk,
            calendars = cols.filter { it.info.isCalendar }.map(::row),
            books = cols.filter { !it.info.isCalendar }.map(::row),
            pending = cols.sumOf { it.pending },
            conflicts = l?.conflicts.orEmpty(),
            push = push,
            theme = p.theme,
            systemAccess = d.access,
        )
    }

    fun reload() {
        viewModelScope.launch {
            loaded.value = withContext(Dispatchers.IO) {
                runCatching {
                    val collections = core.collections()
                    val metas = collections.associate { it.info.id to core.objects(it.info.id) }
                    val labels = collections.associate { it.info.id to it.info.label }
                    val conflicts = core.conflicts().map { c ->
                        val body = when (val d = c.detail) {
                            is ConflictDetail.Rejected -> d.body
                            is ConflictDetail.CollectionGone -> d.body
                            is ConflictDetail.Merged -> null
                        }
                        val title = metas[c.collection]?.firstOrNull { it.name == c.name }?.summary ?: titleIn(body) ?: c.name
                        ConflictRow(c.id, title, labels[c.collection] ?: "", c.at, explain(c.detail))
                    }
                    Loaded(core.account(), core.server(), collections, metas.mapValues { it.value.size }, conflicts.reversed())
                }.getOrNull()
            }
        }
    }

    /** The phone's own state: the network, and push. Read again on every resume. */
    fun look() {
        val push = when {
            !Push.available(context) -> PushState.NO_DISTRIBUTOR
            Push.enabled(context) -> PushState.ON
            else -> PushState.OFF
        }
        device.value = Device(Network.reachable(context), push, bridges.permitted())
    }

    fun syncNow() = SyncWorker.now(context)

    fun setShown(id: String, show: Boolean) {
        viewModelScope.launch {
            prefsStore.setShown(id, show)
            // The bridges add or remove it in Android.
            SyncWorker.now(context)
        }
    }

    /** Calendar and contacts access was just granted (or not), in part or in full. */
    fun accessAnswered() {
        look()
        SyncWorker.now(context)
    }

    fun dismiss(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { core.dismissConflict(id) } }
            reload()
        }
    }

    fun pushOn(activity: Activity) {
        Push.enable(activity) { ok ->
            if (!ok) device.update { it.copy(push = PushState.NO_DISTRIBUTOR) } else look()
        }
    }

    fun pushOff() {
        Push.disable(context)
        look()
    }

    fun setTheme(t: Theme) {
        viewModelScope.launch { prefsStore.setTheme(t) }
    }

    /**
     * Sign out: the server forgets this phone (when it can be reached), the
     * store and the Android account go, and with the account whatever the
     * bridge put in Android's calendar and contacts.
     */
    fun signOut() {
        viewModelScope.launch {
            val s = session.current()
            withContext(Dispatchers.IO) {
                Push.disable(context)
                s?.let { runCatching { core.signOut(it.transport) } }
                AndroidAccounts.remove(context)
            }
            runner.forget()
            prefsStore.clearChoices()
            session.forget()
        }
    }
}

@Composable
fun HomeScreen(onSignInAgain: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.accessAnswered() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.look()
        vm.reload()
    }
    HomeContent(
        ui,
        object : HomeActions {
            override fun syncNow() = vm.syncNow()
            override fun setShown(id: String, show: Boolean) = vm.setShown(id, show)
            override fun dismiss(id: Long) = vm.dismiss(id)
            override fun setPush(on: Boolean) {
                if (on) (context as? Activity)?.let(vm::pushOn) else vm.pushOff()
            }
            override fun setTheme(t: Theme) = vm.setTheme(t)
            override fun openNetworkSettings() = context.startActivity(Network.appSettings(context))
            override fun allowAccess() = askAccess.launch(Bridges.PERMISSIONS)
            override fun signInAgain() = onSignInAgain()
            override fun signOut() = vm.signOut()
        },
    )
}

private fun formatTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
fun HomeContent(ui: HomeUi, actions: HomeActions) {
    val c = Cz.colors
    var confirmSignOut by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().background(c.canvas).safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                ui.account?.let { a ->
                    val host = ui.server?.substringAfter("://")?.substringBefore('/') ?: ""
                    Text(stringResource(R.string.home_who, a.displayName, host), color = c.quiet)
                    Text(stringResource(R.string.home_device, a.deviceLabel), color = c.quiet, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!ui.networkOk) {
            item {
                Banner(Icons.Rounded.CloudOff, stringResource(R.string.network_off_title), stringResource(R.string.network_off_body), c.caution) {
                    OutlinedButton(onClick = actions::openNetworkSettings) { Text(stringResource(R.string.network_open_settings)) }
                }
            }
        }
        if (ui.status.problem == Problem.SIGNED_OUT) {
            item {
                Banner(Icons.Rounded.ErrorOutline, stringResource(R.string.signed_out_title), stringResource(R.string.signed_out_body), c.danger) {
                    Button(onClick = actions::signInAgain) { Text(stringResource(R.string.sign_in_again)) }
                }
            }
        }
        if (!ui.systemAccess) {
            item {
                Banner(Icons.Rounded.CalendarMonth, stringResource(R.string.access_title), stringResource(R.string.access_body), c.accent) {
                    Button(onClick = actions::allowAccess) { Text(stringResource(R.string.access_allow)) }
                }
            }
        }
        item { SyncCard(ui, actions) }
        if (ui.conflicts.isNotEmpty()) {
            item { Heading(stringResource(R.string.conflicts_title), stringResource(R.string.conflicts_hint)) }
            items(ui.conflicts, key = { "conflict-${it.id}" }) { ConflictCard(it) { actions.dismiss(it.id) } }
        }
        item { Heading(stringResource(R.string.calendars_title), stringResource(R.string.collections_hint)) }
        if (ui.calendars.isEmpty()) item { Empty(stringResource(R.string.none_yet)) }
        items(ui.calendars, key = { it.id }) { CollectionCard(it, calendar = true, actions) }
        item { Heading(stringResource(R.string.books_title), null) }
        if (ui.books.isEmpty()) item { Empty(stringResource(R.string.none_yet)) }
        items(ui.books, key = { it.id }) { CollectionCard(it, calendar = false, actions) }
        item { Heading(stringResource(R.string.push_title), null) }
        item { PushCard(ui.push, actions) }
        item { Heading(stringResource(R.string.appearance_title), null) }
        item { ThemeCard(ui.theme, actions) }
        item {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sign_out), color = c.danger)
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_q)) },
            text = {
                Text(
                    if (ui.pending > 0) {
                        pluralStringResource(R.plurals.sign_out_pending, ui.pending, ui.pending)
                    } else {
                        stringResource(R.string.sign_out_body)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    actions.signOut()
                }) { Text(stringResource(R.string.sign_out), color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = Cz.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp)) { content() }
}

@Composable
private fun Heading(title: String, hint: String?) {
    val c = Cz.colors
    Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it, color = c.quiet, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = Cz.colors.quiet, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Banner(icon: ImageVector, title: String, body: String, tint: androidx.compose.ui.graphics.Color, action: @Composable () -> Unit) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(body, color = Cz.colors.quiet)
                action()
            }
        }
    }
}

@Composable
private fun SyncCard(ui: HomeUi, actions: HomeActions) {
    val c = Cz.colors
    val s = ui.status
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        s.running -> stringResource(R.string.sync_running)
                        s.lastOk != null -> stringResource(R.string.sync_last, formatTime(s.lastOk))
                        else -> stringResource(R.string.sync_never)
                    },
                    fontWeight = FontWeight.Bold,
                )
                when (s.problem) {
                    Problem.OFFLINE -> Text(stringResource(R.string.sync_offline), color = c.caution)
                    Problem.FAILED -> Text(stringResource(R.string.sync_failed, s.detail ?: ""), color = c.danger)
                    Problem.SIGNED_OUT, null -> {}
                }
                if (ui.pending > 0) Text(pluralStringResource(R.plurals.sync_pending, ui.pending, ui.pending), color = c.quiet)
            }
            if (s.running) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Button(
                    onClick = actions::syncNow,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                ) {
                    Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.sync_now))
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(row: CollectionRow, calendar: Boolean, actions: HomeActions) {
    val c = Cz.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(parseColor(row.color) ?: c.accent))
            Column(Modifier.weight(1f)) {
                Text(row.label, fontWeight = FontWeight.SemiBold)
                val bits = buildList {
                    add(
                        if (calendar) {
                            pluralStringResource(R.plurals.count_events, row.count, row.count)
                        } else {
                            pluralStringResource(R.plurals.count_contacts, row.count, row.count)
                        },
                    )
                    row.owner?.let { add(stringResource(R.string.shared_by, it)) }
                    if (row.subscribed) add(stringResource(R.string.badge_subscribed)) else if (row.readOnly) add(stringResource(R.string.badge_view_only))
                }
                Text(bits.joinToString(" · "), color = c.quiet, style = MaterialTheme.typography.bodySmall)
            }
            val label = stringResource(R.string.show_on_phone, row.label)
            Switch(
                checked = row.shown,
                onCheckedChange = { actions.setShown(row.id, it) },
                modifier = Modifier.semantics { contentDescription = label },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
    }
}

@Composable
private fun PushCard(state: PushState, actions: HomeActions) {
    val c = Cz.colors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.push_name), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        when (state) {
                            PushState.SERVER_OFF -> R.string.push_server_off
                            PushState.NO_DISTRIBUTOR -> R.string.push_no_distributor
                            PushState.OFF -> R.string.push_off
                            PushState.ON -> R.string.push_on
                        },
                    ),
                    color = c.quiet,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val name = stringResource(R.string.push_name)
            Switch(
                checked = state == PushState.ON,
                enabled = state == PushState.ON || state == PushState.OFF,
                onCheckedChange = actions::setPush,
                modifier = Modifier.semantics { contentDescription = name },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
    }
}

@Composable
private fun ThemeCard(theme: Theme, actions: HomeActions) {
    Card {
        val options = listOf(Theme.DARK to R.string.theme_dark, Theme.LIGHT to R.string.theme_light, Theme.SYSTEM to R.string.theme_system)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (t, label) ->
                SegmentedButton(
                    selected = theme == t,
                    onClick = { actions.setTheme(t) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(stringResource(label)) }
            }
        }
    }
}

@Composable
private fun fieldName(e: Explanation.Field): String = when (e.label) {
    FieldLabel.TITLE -> stringResource(R.string.field_title)
    FieldLabel.TIME -> stringResource(R.string.field_time)
    FieldLabel.PLACE -> stringResource(R.string.field_place)
    FieldLabel.NOTES -> stringResource(R.string.field_notes)
    FieldLabel.REPEAT -> stringResource(R.string.field_repeat)
    FieldLabel.STATUS -> stringResource(R.string.field_status)
    FieldLabel.NAME -> stringResource(R.string.field_name)
    FieldLabel.PHONE -> stringResource(R.string.field_phone)
    FieldLabel.EMAIL -> stringResource(R.string.field_email)
    FieldLabel.ADDRESS -> stringResource(R.string.field_address)
    FieldLabel.BIRTHDAY -> stringResource(R.string.field_birthday)
    FieldLabel.ORGANIZATION -> stringResource(R.string.field_organization)
    FieldLabel.OTHER -> e.raw
}

@Composable
fun explanationText(e: Explanation): String = when (e) {
    is Explanation.Field -> stringResource(R.string.conflict_field, fieldName(e), e.kept, e.lost)
    Explanation.DeleteUndone -> stringResource(R.string.conflict_delete_undone)
    Explanation.EditOnDeleted -> stringResource(R.string.conflict_edit_on_deleted)
    is Explanation.Refused -> stringResource(
        when (e.reason) {
            "read_only" -> R.string.conflict_read_only
            "uid_taken" -> R.string.conflict_uid_taken
            else -> R.string.conflict_invalid
        },
    )
    Explanation.CollectionGone -> stringResource(R.string.conflict_collection_gone)
}

@Composable
private fun ConflictCard(row: ConflictRow, onDismiss: () -> Unit) {
    val c = Cz.colors
    Card {
        Text(row.title, fontWeight = FontWeight.SemiBold)
        Text(
            listOf(row.collection, formatTime(row.at * 1000)).filter { it.isNotBlank() }.joinToString(" · "),
            color = c.quiet,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        row.lines.forEach { Text("• " + explanationText(it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_dismiss)) }
        }
    }
}
