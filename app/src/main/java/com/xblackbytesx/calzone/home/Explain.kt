package com.xblackbytesx.calzone.home

import com.xblackbytesx.calzone.model.ConflictDetail
import com.xblackbytesx.calzone.model.Lost

/** The thing a field is about, in words people use (the screen translates it). */
enum class FieldLabel { TITLE, TIME, PLACE, NOTES, REPEAT, STATUS, NAME, PHONE, EMAIL, ADDRESS, BIRTHDAY, ORGANIZATION, OTHER }

/** One line of a conflict, for the settings screen (pure, so it is tested). */
sealed interface Explanation {
    /** Both sides changed this: the server's value stayed, the phone's did not. */
    data class Field(val label: FieldLabel, val raw: String, val kept: String, val lost: String) : Explanation

    /** The phone deleted it, someone changed it meanwhile: it was kept. */
    data object DeleteUndone : Explanation

    /** Someone deleted it while the phone changed it: the change is gone. */
    data object EditOnDeleted : Explanation

    /** The server refused the edit; `reason` is the server's code. */
    data class Refused(val reason: String) : Explanation

    /** The calendar or address book went away with this edit in it. */
    data object CollectionGone : Explanation
}

fun label(property: String): FieldLabel = when (property.uppercase()) {
    "SUMMARY" -> FieldLabel.TITLE
    "TIME", "DTSTART", "DTEND", "DURATION", "DUE" -> FieldLabel.TIME
    "LOCATION" -> FieldLabel.PLACE
    "DESCRIPTION", "NOTE" -> FieldLabel.NOTES
    "RRULE", "RDATE", "EXDATE" -> FieldLabel.REPEAT
    "STATUS" -> FieldLabel.STATUS
    "FN", "N" -> FieldLabel.NAME
    "TEL" -> FieldLabel.PHONE
    "EMAIL" -> FieldLabel.EMAIL
    "ADR" -> FieldLabel.ADDRESS
    "BDAY" -> FieldLabel.BIRTHDAY
    "ORG", "TITLE" -> FieldLabel.ORGANIZATION
    else -> FieldLabel.OTHER
}

/** A content line's name (without group or parameters). */
private fun nameOf(line: String): String =
    line.substringBefore(':').substringBefore(';').substringAfterLast('.').uppercase()

/** A content line's value: after the first `:` outside quotes, unescaped. */
fun valueOf(line: String): String {
    var quoted = false
    var at = -1
    for ((i, ch) in line.withIndex()) {
        if (ch == '"') quoted = !quoted
        if (ch == ':' && !quoted) {
            at = i
            break
        }
    }
    val raw = if (at < 0) line else line.substring(at + 1)
    return raw.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}

/** `20260310T090000Z` as `2026-03-10 09:00`; `20260310` as `2026-03-10`; anything else as is. */
fun prettyTime(v: String): String {
    val m = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})\d{2}Z?)?$""").matchEntire(v.trim()) ?: return v
    val (y, mo, d, h, mi) = m.destructured
    return if (h.isEmpty()) "$y-$mo-$d" else "$y-$mo-$d $h:$mi"
}

/** The time lines of one side, as "start to end" (or the duration). */
private fun times(lines: List<String>): String {
    val byName = lines.associateBy(::nameOf)
    val start = byName["DTSTART"]?.let { prettyTime(valueOf(it)) }
    val end = (byName["DTEND"] ?: byName["DUE"])?.let { prettyTime(valueOf(it)) }
    val duration = byName["DURATION"]?.let(::valueOf)
    return when {
        start != null && end != null -> {
            val sameDay = start.length > 10 && end.length > 10 && start.take(10) == end.take(10)
            "$start – ${if (sameDay) end.drop(11) else end}"
        }
        start != null && duration != null -> "$start ($duration)"
        else -> (start ?: end ?: duration ?: "")
    }
}

private fun values(lines: List<String>): String = lines.joinToString(", ") { valueOf(it) }

fun explain(d: ConflictDetail): List<Explanation> = when (d) {
    is ConflictDetail.Merged -> d.conflicts.map { c ->
        when (val l = c.lost) {
            is Lost.Field -> {
                val label = label(l.field)
                if (label == FieldLabel.TIME) {
                    Explanation.Field(label, l.field, times(l.kept), times(l.lost))
                } else {
                    Explanation.Field(label, l.field, values(l.kept), values(l.lost))
                }
            }
            Lost.Delete -> Explanation.DeleteUndone
            is Lost.Edit -> Explanation.EditOnDeleted
        }
    }
    is ConflictDetail.Rejected -> listOf(Explanation.Refused(d.reason))
    is ConflictDetail.CollectionGone -> listOf(Explanation.CollectionGone)
}

/** The title (SUMMARY) or name (FN) in an object's text, for naming a conflict. */
fun titleIn(body: String?): String? = body
    ?.lineSequence()
    ?.map { it.trimEnd('\r') }
    ?.firstOrNull { nameOf(it) == "SUMMARY" || nameOf(it) == "FN" }
    ?.let(::valueOf)
    ?.takeIf { it.isNotBlank() }
