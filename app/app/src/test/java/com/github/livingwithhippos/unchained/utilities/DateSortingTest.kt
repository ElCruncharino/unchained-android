package com.github.livingwithhippos.unchained.utilities

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateSortingTest {

    @Test
    fun `parses a real debrid style timestamp with milliseconds and Z suffix`() {
        val instant = parseIsoInstantOrNull("2015-03-27T22:07:22.000Z")
        assertEquals(Instant.parse("2015-03-27T22:07:22.000Z"), instant)
    }

    @Test
    fun `parses a timestamp without fractional seconds`() {
        val instant = parseIsoInstantOrNull("2023-06-05T14:23:41Z")
        assertEquals(Instant.parse("2023-06-05T14:23:41Z"), instant)
    }

    @Test
    fun `parses a timestamp with a numeric offset instead of Z`() {
        val instant = parseIsoInstantOrNull("2023-06-05T14:23:41+02:00")
        assertEquals(Instant.parse("2023-06-05T12:23:41Z"), instant)
    }

    @Test
    fun `returns null for a blank or unparsable value`() {
        assertNull(parseIsoInstantOrNull(null))
        assertNull(parseIsoInstantOrNull(""))
        assertNull(parseIsoInstantOrNull("not a date"))
    }

    private data class Item(val name: String, val added: String?)

    @Test
    fun `interleaves a recent torbox item ahead of an older real debrid item`() {
        val now = Instant.now()
        val realDebridItem = Item("real-debrid torrent", now.minus(7, ChronoUnit.DAYS).toString())
        val torBoxItem = Item("torbox torrent", now.minus(1, ChronoUnit.HOURS).toString())

        val sorted = listOf(realDebridItem, torBoxItem).sortedByRecencyDescending { it.added }

        assertEquals(listOf(torBoxItem, realDebridItem), sorted)
    }

    @Test
    fun `sorts a mixed list most recent first and pushes unparsable dates last`() {
        val now = Instant.now()
        val oldest = Item("oldest", now.minus(10, ChronoUnit.DAYS).toString())
        val middle = Item("middle", now.minus(2, ChronoUnit.DAYS).toString())
        val newest = Item("newest", now.minus(1, ChronoUnit.HOURS).toString())
        val broken = Item("broken date", "not a date")
        val missing = Item("missing date", null)

        val sorted =
            listOf(oldest, broken, middle, missing, newest).sortedByRecencyDescending { it.added }

        assertEquals(listOf(newest, middle, oldest), sorted.take(3))
        // items with an unparsable or missing date sort last, in their original relative order
        assertEquals(setOf(broken, missing), sorted.drop(3).toSet())
    }
}
