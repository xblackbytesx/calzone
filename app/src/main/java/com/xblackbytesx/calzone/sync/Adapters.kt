package com.xblackbytesx.calzone.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun runner(): SyncRunner
}

private fun runner(context: Context): SyncRunner =
    EntryPointAccessors.fromApplication(context.applicationContext, SyncEntryPoint::class.java).runner()

/** The account exists so the sync adapters have something to hang on; it has no credentials of its own. */
class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
    override fun editProperties(r: AccountAuthenticatorResponse?, t: String?): Bundle? = null
    override fun addAccount(r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?): Bundle? = null
    override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?): Bundle? = null
    override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
    override fun getAuthTokenLabel(t: String?): String? = null
    override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
    override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?): Bundle? = null
}

class AuthenticatorService : Service() {
    private lateinit var auth: Authenticator
    override fun onCreate() {
        auth = Authenticator(this)
    }
    override fun onBind(intent: Intent?): IBinder = auth.iBinder
}

/**
 * Android's trigger (decision 45): an edit in Fossify or Contacts (upload),
 * their refresh, our own request, the daily sync. It only starts the
 * engine; mirroring into Android's tables comes with the bridge (steps 5
 * and 6). The result tells Android whether to retry with backoff.
 */
class EngineSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
        when (val o = runBlocking { runner(context).run() }) {
            is Outcome.Ok, Outcome.NotSignedIn -> {}
            is Outcome.Failed -> when (o.problem) {
                // Android retries later with backoff.
                Problem.OFFLINE -> result.stats.numIoExceptions++
                Problem.SIGNED_OUT -> result.stats.numAuthExceptions++
                Problem.FAILED -> result.databaseError = true
            }
        }
    }
}

class CalendarSyncService : Service() {
    private lateinit var adapter: EngineSyncAdapter
    override fun onCreate() {
        adapter = EngineSyncAdapter(applicationContext)
    }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

class ContactsSyncService : Service() {
    private lateinit var adapter: EngineSyncAdapter
    override fun onCreate() {
        adapter = EngineSyncAdapter(applicationContext)
    }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

/** A sync for a push or "Sync now": expedited, once the network is there. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val o = runner(applicationContext).run()) {
        is Outcome.Ok, Outcome.NotSignedIn -> Result.success()
        is Outcome.Failed -> if (o.problem == Problem.OFFLINE && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "sync-now"

        /**
         * Ask for a run soon. A request that comes while one is running is
         * queued after it, so a change announced mid-run is fetched; the
         * server gathers its pushes for two seconds, so the queue stays short.
         */
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
