package com.xblackbytesx.calzone.push

import android.app.Activity
import android.content.Context
import com.xblackbytesx.calzone.account.Session
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Notice
import com.xblackbytesx.calzone.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Starting and stopping UnifiedPush (a distributor such as ntfy must be installed). */
object Push {
    fun available(context: Context): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /** Uses the distributor already chosen, or the phone's default; false when there is none. */
    fun enable(activity: Activity, done: (Boolean) -> Unit) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(activity)
            done(ok)
        }
    }

    fun disable(context: Context) {
        runCatching { UnifiedPush.unregister(context) }
    }

    fun enabled(context: Context): Boolean = UnifiedPush.getAckDistributor(context) != null

    /** After signing in again: the distributor hands the endpoint over once more, for the new token. */
    fun reregister(context: Context) {
        runCatching { UnifiedPush.register(context) }
    }
}

/**
 * UnifiedPush events (decision 16 in calzone-server). A new endpoint goes to
 * the server with its WebPush keys (the server encrypts to them and accepts
 * nothing else); a message that did not decrypt is dropped, so knowing the
 * endpoint is not enough to wake the phone. A message only says which
 * collections changed: it starts a sync, which fetches the rest.
 */
@AndroidEntryPoint
class PushReceiver : PushService() {
    @Inject lateinit var core: Core
    @Inject lateinit var session: Session

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: return
        scope.launch {
            val s = session.current() ?: return@launch
            runCatching {
                core.registerPush(s.transport, endpoint.url, keys.pubKey, keys.auth)
                core.refreshAccount(s.transport)
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return
        // Only a well-formed notice counts; its content is not needed beyond that.
        runCatching { CzJson.decodeFromString(Notice.serializer(), message.content.decodeToString()) }.getOrNull() ?: return
        SyncWorker.now(this)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {}

    override fun onUnregistered(instance: String) {
        scope.launch {
            val s = session.current() ?: return@launch
            runCatching {
                core.unregisterPush(s.transport)
                core.refreshAccount(s.transport)
            }
        }
    }
}
