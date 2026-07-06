package com.github.livingwithhippos.unchained.utilities

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import timber.log.Timber

/**
 * Parses an ISO-8601 date/time string into a comparable [Instant]. Both Real-Debrid ("added",
 * "generated", "ended") and TorBox ("created_at") return this kind of timestamp, just not
 * necessarily in the exact same shape (with/without a "Z" suffix, fractional seconds, an explicit
 * numeric offset...), so [Instant.parse] is tried first and [OffsetDateTime.parse] as a fallback
 * for the looser variants. Returns null instead of throwing on a blank or unparsable value, so a
 * caller sorting mixed sources can push the offending item to the end rather than crash
 */
fun parseIsoInstantOrNull(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value)
    } catch (e: DateTimeParseException) {
        try {
            OffsetDateTime.parse(value).toInstant()
        } catch (e2: DateTimeParseException) {
            Timber.w("Could not parse date/time value: $value")
            null
        }
    }
}

/**
 * sorts the receiver most recent first, using [dateSelector] parsed with [parseIsoInstantOrNull].
 * Used to interleave items coming from different sources (real debrid and torbox) by actual
 * recency instead of showing one source as a trailing block; an item whose date fails to parse
 * sorts last instead of breaking the whole list
 */
fun <T> List<T>.sortedByRecencyDescending(dateSelector: (T) -> String?): List<T> =
    sortedByDescending { parseIsoInstantOrNull(dateSelector(it)) ?: Instant.MIN }
