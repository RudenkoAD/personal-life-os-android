package com.personallifeos.mobile.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A timed item positioned in the selected day's half-open minute range. */
data class DayEventBlock(
    val item: CalendarItem,
    val startMinute: Int,
    val endMinute: Int,
    val column: Int,
    val columnCount: Int,
)

/** The all-day and hourly portions of one local calendar day. */
data class DayTimeline(
    val date: LocalDate,
    val allDay: List<CalendarItem>,
    val timed: List<DayEventBlock>,
)

/** Builds the geometry consumed by both the calendar screen and its widget. */
object DayTimelineLayout {
    private const val MINUTES_PER_DAY = 24 * 60

    fun layout(date: LocalDate, items: List<CalendarItem>): DayTimeline {
        val dayStart = date.atStartOfDay(CalendarProjection.zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(CalendarProjection.zone).toInstant()

        val allDay = items.asSequence()
            .filter { it.allDay && validAndOverlaps(it, dayStart, dayEnd) }
            .sortedWith(itemComparator)
            .toList()

        val timed = items.asSequence()
            .filter { !it.allDay }
            .mapNotNull { item -> clip(item, dayStart, dayEnd) }
            .sortedWith(compareBy<Clipped> { it.startMinute }.thenBy { it.endMinute }.thenBy { it.item.id }
                .thenBy { it.item.title })
            .toList()

        val blocks = mutableListOf<DayEventBlock>()
        var groupStart = 0
        while (groupStart < timed.size) {
            var groupEnd = groupStart + 1
            var furthestEnd = timed[groupStart].endMinute
            while (groupEnd < timed.size && timed[groupEnd].startMinute < furthestEnd) {
                furthestEnd = maxOf(furthestEnd, timed[groupEnd].endMinute)
                groupEnd++
            }
            blocks += assignLanes(timed.subList(groupStart, groupEnd))
            groupStart = groupEnd
        }

        return DayTimeline(date = date, allDay = allDay, timed = blocks)
    }

    private fun assignLanes(group: List<Clipped>): List<DayEventBlock> {
        val laneEnds = mutableListOf<Int>()
        val assignments = group.map { event ->
            val lane = laneEnds.indexOfFirst { end -> end <= event.startMinute }
                .takeIf { it >= 0 }
                ?: laneEnds.size.also { laneEnds += 0 }
            laneEnds[lane] = event.endMinute
            lane to event
        }
        val columnCount = laneEnds.size
        return assignments.map { (column, event) ->
            DayEventBlock(event.item, event.startMinute, event.endMinute, column, columnCount)
        }
    }

    private fun validAndOverlaps(item: CalendarItem, dayStart: Instant, dayEnd: Instant): Boolean =
        item.end.isAfter(item.start) && item.start.isBefore(dayEnd) && item.end.isAfter(dayStart)

    private fun clip(item: CalendarItem, dayStart: Instant, dayEnd: Instant): Clipped? {
        if (!validAndOverlaps(item, dayStart, dayEnd)) return null
        val start = maxOf(item.start, dayStart)
        val end = minOf(item.end, dayEnd)
        val startMinute = elapsedMinutes(dayStart, start, roundUp = false)
        val endMinute = elapsedMinutes(dayStart, end, roundUp = true)
        if (endMinute <= startMinute) return null
        return Clipped(item, startMinute, endMinute)
    }

    private fun elapsedMinutes(dayStart: Instant, instant: Instant, roundUp: Boolean): Int {
        val duration = Duration.between(dayStart, instant)
        val seconds = duration.seconds
        val minute = seconds / 60
        val hasRemainder = duration.nano != 0 || seconds % 60 != 0L
        val rounded = if (roundUp && hasRemainder) minute + 1 else minute
        return rounded.toInt().coerceIn(0, MINUTES_PER_DAY)
    }

    private data class Clipped(val item: CalendarItem, val startMinute: Int, val endMinute: Int)

    private val itemComparator = compareBy<CalendarItem> { it.start }
        .thenBy { it.end }
        .thenBy { it.id }
        .thenBy { it.title }
}
