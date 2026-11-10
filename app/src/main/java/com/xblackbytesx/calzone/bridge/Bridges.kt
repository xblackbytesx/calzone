package com.xblackbytesx.calzone.bridge

import android.util.Log
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.prefs.Prefs
import android.accounts.Account as AndroidAccount

/** One run's writes into Android, after the engine ran. */
interface Pass {
    /** Mirror one object the server changed, when it is in one of this bridge's collections. */
    fun write(collection: String, name: String)

    /** Everything was written: remember what Android holds now. */
    suspend fun finish()
}

/** A bridge between the store and one of Android's providers. */
interface Mirror {
    fun permitted(): Boolean

    /** Android's edits since the last run into pending edits (before the engine runs). */
    fun outbound(a: AndroidAccount, prefs: Prefs)

    /** The collections this phone shows, and whatever must be written in full. */
    suspend fun begin(a: AndroidAccount, prefs: Prefs): Pass

    /** The next run writes everything again (no access now, or a run broke off). */
    suspend fun forget()
}

/**
 * Both bridges around every engine run (decisions 13 and 17): their
 * outbound halves before it, then one pass over the store's local change
 * log, each change handed to the bridge whose collection it is in. The log
 * is read once, so a bridge without access (or one that failed) writes
 * everything again on its next good run instead of missing a change. A
 * failure is logged without content and never stops a sync.
 */
class Bridges(private val core: Core, calendar: CalendarBridge, contacts: ContactsBridge) {
    private val all: List<Mirror> = listOf(calendar, contacts)

    /** Both may write (the main screen asks until they may). */
    fun permitted(): Boolean = all.all { it.permitted() }

    fun outbound(a: AndroidAccount, prefs: Prefs) {
        for (m in all) if (m.permitted()) guarded(m) { m.outbound(a, prefs) }
    }

    suspend fun inbound(a: AndroidAccount, prefs: Prefs) {
        val passes = mutableListOf<Pass>()
        for (m in all) {
            if (!m.permitted()) {
                guarded(m) { m.forget() }
                continue
            }
            try {
                passes += m.begin(a, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "${m.javaClass.simpleName}: ${e.javaClass.simpleName}")
                guarded(m) { m.forget() }
            }
        }
        try {
            while (true) {
                val changes = core.localChanges(0, 500)
                if (changes.isEmpty()) break
                for (ch in changes) {
                    val name = ch.name ?: continue
                    for (p in passes) {
                        try {
                            p.write(ch.collection, name)
                        } catch (e: Exception) {
                            Log.w(TAG, "a change was not written to Android: ${e.javaClass.simpleName}")
                        }
                    }
                }
                core.forgetLocalChanges(changes.last().seq)
            }
        } catch (e: Exception) {
            Log.w(TAG, "the change log: ${e.javaClass.simpleName}")
            for (m in all) guarded(m) { m.forget() }
            return
        }
        for (p in passes) {
            try {
                p.finish()
            } catch (e: Exception) {
                Log.w(TAG, "finishing a pass: ${e.javaClass.simpleName}")
            }
        }
    }

    private inline fun guarded(m: Mirror, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "${m.javaClass.simpleName}: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "CalZoneBridge"

        /** Everything the main screen asks for at once. */
        val PERMISSIONS = CalendarBridge.PERMISSIONS + ContactsBridge.PERMISSIONS
    }
}
