package com.xblackbytesx.calzone.reminders

import com.xblackbytesx.calzone.model.Due

/**
 * When reminders ring (decision 32), as pure rules: the ring and the
 * schedule both start from the last moment rung, so an alarm that came
 * late, a phone that was off, or a reminder set for a minute ago all ring
 * as soon as they can. Times are UTC seconds.
 */
object Timing {
    /** A missed reminder older than this is dropped, not rung. */
    const val MISSED = 24 * 3600L

    /** How far ahead the schedule looks (and the core finds reminders). */
    const val AHEAD = 35 * 86_400L

    /** A snooze. */
    const val SNOOZE = 10 * 60L

    /**
     * The last moment rung as the rules use it: now on the first run (so a
     * fresh install does not ring the past), now again when the clock went
     * back (else nothing would ring until it caught up).
     */
    fun last(stored: Long?, now: Long): Long = when {
        stored == null -> now
        stored > now + 60 -> now
        else -> stored
    }

    /** Reminders that ring now are those due in `(last, now]`, at most a day back: `[from, to)` for the core. */
    fun window(last: Long, now: Long): Pair<Long, Long> = maxOf(last + 1, now - MISSED) to now + 1

    /** Where the schedule starts looking: the same lower bound, so a due moment not yet rung is set at once. */
    fun scheduleFrom(last: Long, now: Long): Long = maxOf(last + 1, now - MISSED)

    /** A missed reminder is still worth showing while its event lasts, or within an hour of its time. */
    fun worthRinging(d: Due, now: Long): Boolean = maxOf(d.occurrence.endAt, d.fire + 3600) > now

    /**
     * Before the first unlock (decision 51): the times due in `(upTo, now]`,
     * for "a reminder at 09:00; unlock to see it".
     */
    fun lockedDue(times: List<Long>, upTo: Long, now: Long): List<Long> = times.filter { it in (upTo + 1)..now }

    /** The next alarm: the earliest time after `after`, or none. */
    fun next(times: List<Long>, after: Long): Long? = times.filter { it > after }.minOrNull()
}
