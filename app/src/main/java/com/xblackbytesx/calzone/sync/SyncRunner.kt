package com.xblackbytesx.calzone.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import com.xblackbytesx.calzone.account.AndroidAccounts
import com.xblackbytesx.calzone.account.Session
import com.xblackbytesx.calzone.bridge.Bridges
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.core.CoreError
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Report
import com.xblackbytesx.calzone.prefs.PrefsStore
import com.xblackbytesx.calzone.reminders.Reminders
import com.xblackbytesx.calzone.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.syncStore by preferencesDataStore("sync")

/** How the last run ended. */
enum class Problem {
    /** No answer from the server (no network, or the Network permission is off). */
    OFFLINE,

    /** The server no longer knows this phone. */
    SIGNED_OUT,

    /** Anything else: an unexpected answer, a store failure. */
    FAILED,
}

data class SyncStatus(
    /** UTC milliseconds of the last run that finished, or null. */
    val lastOk: Long? = null,
    val lastReport: Report? = null,
    /** The last run failed this way (cleared by the next good run). */
    val problem: Problem? = null,
    val detail: String? = null,
    val running: Boolean = false,
)

sealed interface Outcome {
    data class Ok(val report: Report) : Outcome
    data class Failed(val problem: Problem, val detail: String) : Outcome
    data object NotSignedIn : Outcome
}

/**
 * Runs the engine, one run at a time (decision 3): Android's sync adapters
 * (calendar and contacts) and WorkManager (a push, "Sync now") all come
 * here, and a request that arrives during a run waits and then runs, so a
 * change that arrived meanwhile is never missed. A run with nothing to do
 * is one request, so repeats are cheap. The outcome is kept for the
 * settings screen.
 *
 * Each run is wrapped by the bridges that may write Android's calendar
 * and contacts (decisions 13 and 17): Android's edits are read into the
 * store before the engine runs, and the server's changes are written into
 * Android after it, also when the server could not be reached (the phone's
 * own choices still apply).
 */
class SyncRunner(
    private val context: Context,
    private val core: Core,
    private val session: Session,
    private val bridges: Bridges,
    private val prefs: PrefsStore,
    private val reminders: Reminders,
) {
    private val mutex = Mutex()
    private val running = MutableStateFlow(false)
    private val lastOkKey = longPreferencesKey("last_ok")
    private val reportKey = stringPreferencesKey("last_report")
    private val problemKey = stringPreferencesKey("problem")
    private val detailKey = stringPreferencesKey("detail")

    val status: Flow<SyncStatus> = combine(context.syncStore.data, running) { p, r ->
        SyncStatus(
            lastOk = p[lastOkKey],
            lastReport = p[reportKey]?.let { runCatching { CzJson.decodeFromString(Report.serializer(), it) }.getOrNull() },
            problem = p[problemKey]?.let { runCatching { Problem.valueOf(it) }.getOrNull() },
            detail = p[detailKey],
            running = r,
        )
    }

    suspend fun run(): Outcome = mutex.withLock {
        val s = session.current() ?: return@withLock Outcome.NotSignedIn
        running.value = true
        try {
            val account = AndroidAccounts.current(context)
            val outcome = withContext(Dispatchers.IO) {
                val shown = prefs.prefs.first()
                account?.let { a -> guarded { bridges.outbound(a, shown) } }
                try {
                    Outcome.Ok(core.sync(s.transport))
                } catch (e: CoreError.Offline) {
                    Outcome.Failed(Problem.OFFLINE, e.message ?: "")
                } catch (e: CoreError.SignedOut) {
                    Outcome.Failed(Problem.SIGNED_OUT, e.message ?: "")
                } catch (e: CoreError) {
                    Outcome.Failed(Problem.FAILED, e.message ?: "")
                }.also {
                    account?.let { a -> guarded { bridges.inbound(a, prefs.prefs.first()) } }
                }
            }
            record(outcome)
            // What came in, and edits made here, may move the next reminder; offline too.
            reminders.reschedule()
            Widgets.update(context)
            outcome
        } finally {
            running.value = false
        }
    }

    /** The bridges never stop a sync: a failure is logged (without content) and tried again next run. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w("CalZoneSync", "bridge: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun record(o: Outcome) {
        context.syncStore.edit {
            when (o) {
                is Outcome.Ok -> {
                    it[lastOkKey] = System.currentTimeMillis()
                    it[reportKey] = CzJson.encodeToString(Report.serializer(), o.report)
                    it.remove(problemKey)
                    it.remove(detailKey)
                }
                is Outcome.Failed -> {
                    it[problemKey] = o.problem.name
                    it[detailKey] = o.detail.take(300)
                }
                Outcome.NotSignedIn -> {}
            }
        }
    }

    /** Signing in again or out starts from a clean slate. */
    suspend fun forget() {
        context.syncStore.edit { it.clear() }
    }
}
