package com.xblackbytesx.calzone.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Due
import com.xblackbytesx.calzone.widget.Widgets
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Reached from receivers without injection, only once the store can be read (after the first unlock). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntry {
    fun reminders(): Reminders
}

private fun reminders(context: Context): Reminders =
    EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntry::class.java).reminders()

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs `block` past `onReceive`, as long as Android allows a receiver (about 10 seconds). */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/**
 * Before the first unlock nothing but device-protected storage can be read:
 * a reminder then says when, not what, and the next time is set from the
 * times kept there (decision 51).
 */
private fun ringLocked(context: Context) {
    val t = Times(context)
    val now = System.currentTimeMillis() / 1000
    val upTo = maxOf(t.lockedUpTo, Timing.last(t.last, now))
    Notices.postLocked(context, Timing.lockedDue(t.times, upTo, now))
    t.lockedUpTo = now
    Alarm.fromTimes(context, now)
}

/** The alarm, and the notification's Snooze. Direct-boot aware: it may ring before the first unlock. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RING -> if (!unlocked(context)) ringLocked(context) else async { reminders(context).ring() }
            SNOOZE -> {
                // A Snooze tapped on the lock screen before the first unlock cannot be kept; the notification stays.
                if (!unlocked(context)) return
                val due = intent.getStringExtra(EXTRA_DUE)?.let { runCatching { CzJson.decodeFromString(Due.serializer(), it) }.getOrNull() } ?: return
                async { reminders(context).snooze(due) }
            }
        }
    }

    companion object {
        const val RING = "com.xblackbytesx.calzone.reminders.RING"
        const val SNOOZE = "com.xblackbytesx.calzone.reminders.SNOOZE"
        const val EXTRA_DUE = "due"
    }
}

/**
 * Alarms are gone after a reboot and wrong after a clock or zone change:
 * set again, at once on the locked boot from the kept times, and from the
 * store once it can be read (decision 51). Ringing also shows what was
 * missed meanwhile and replaces the "unlock to see it" notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> if (!unlocked(context)) ringLocked(context) else async { reminders(context).ring() }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> if (unlocked(context)) {
                async {
                    reminders(context).ring()
                    // The widgets' today and times too.
                    Widgets.update(context.applicationContext)
                }
            } else {
                ringLocked(context)
            }
        }
    }
}
