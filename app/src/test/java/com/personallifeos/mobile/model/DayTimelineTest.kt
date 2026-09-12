package com.personallifeos.mobile.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DayTimelineTest {
    private val date = LocalDate.of(2026, 9, 12)

    @Test
    fun overlappingChainUsesConnectedGroupAndStableLanes() {
        val result = DayTimelineLayout.layout(date, listOf(
            item("c", "10:30", "12:00"),
            item("b", "09:30", "11:00"),
            item("a", "09:00", "10:00"),
        ))

        assertEquals(listOf("a", "b", "c"), result.timed.map { it.item.id })
        assertEquals(listOf(0, 1, 0), result.timed.map { it.column })
        assertEquals(listOf(2, 2, 2), result.timed.map { it.columnCount })
    }

    @Test
    fun adjacentEventsDoNotShareAConnectedGroup() {
        val result = DayTimelineLayout.layout(date, listOf(
            item("later", "10:00", "11:00"),
            item("first", "09:00", "10:00"),
        ))

        assertEquals(listOf(0, 0), result.timed.map { it.column })
        assertEquals(listOf(1, 1), result.timed.map { it.columnCount })
    }

    @Test
    fun clipsMidnightAndUsesExclusiveEnd() {
        val result = DayTimelineLayout.layout(date, listOf(
            item("before", "previous", "00:00"),
            item("overnight", "previous", "08:15"),
            item("after", "00:00-next", "01:00-next"),
        ))

        assertEquals(listOf("overnight"), result.timed.map { it.item.id })
        assertEquals(0, result.timed.single().startMinute)
        assertEquals(8 * 60 + 15, result.timed.single().endMinute)
    }

    @Test
    fun clipsEventsContinuingIntoNextDayAndExcludesEventsStartingAtDayEnd() {
        val result = DayTimelineLayout.layout(date, listOf(
            item("continues", "23:00", "01:00-next"),
            item("later", "00:00-next", "01:00-next"),
        ))

        assertEquals(listOf("continues"), result.timed.map { it.item.id })
        assertEquals(23 * 60, result.timed.single().startMinute)
        assertEquals(24 * 60, result.timed.single().endMinute)
    }

    @Test
    fun threeSimultaneousEventsUseThreeLanesAndLaterIndependentEventUsesOne() {
        val result = DayTimelineLayout.layout(date, listOf(
            item("third", "09:00", "10:00"),
            item("later", "11:00", "12:00"),
            item("first", "09:00", "10:00"),
            item("second", "09:00", "10:00"),
        ))

        assertEquals(listOf("first", "second", "third", "later"), result.timed.map { it.item.id })
        assertEquals(listOf(0, 1, 2, 0), result.timed.map { it.column })
        assertEquals(listOf(3, 3, 3, 1), result.timed.map { it.columnCount })
    }

    @Test
    fun separatesAllDayAndExcludesInvalidOrNonOverlappingItems() {
        val result = DayTimelineLayout.layout(date, listOf(
            item("all", "00:00", "00:00-next", allDay = true),
            item("invalid", "11:00", "11:00"),
            item("zero", "12:00", "12:00"),
        ))

        assertEquals(listOf("all"), result.allDay.map { it.id })
        assertEquals(emptyList<String>(), result.timed.map { it.item.id })
    }

    @Test
    fun orderingIsIndependentOfInputOrder() {
        val items = listOf(item("b", "09:00", "10:00"), item("a", "09:00", "10:00"))
        val first = DayTimelineLayout.layout(date, items)
        val second = DayTimelineLayout.layout(date, items.reversed())
        assertEquals(first, second)
    }

    private fun item(id: String, start: String, end: String, allDay: Boolean = false): CalendarItem {
        fun instant(value: String): Instant = when (value) {
            "previous" -> date.minusDays(1).atTime(23, 0).atZone(CalendarProjection.zone).toInstant()
            "00:00-next" -> date.plusDays(1).atStartOfDay(CalendarProjection.zone).toInstant()
            "01:00-next" -> date.plusDays(1).atTime(1, 0).atZone(CalendarProjection.zone).toInstant()
            else -> date.atTime(LocalTime.parse(value)).atZone(CalendarProjection.zone).toInstant()
        }
        return CalendarItem(id, id, instant(start), instant(end), allDay, "#000000")
    }
}
