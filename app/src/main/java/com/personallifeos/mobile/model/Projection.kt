package com.personallifeos.mobile.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun copyState(state: LifeState): LifeState = state.copy(
    cards = state.cards.toList(),
    boards = state.boards.toList(),
    tags = state.tags.toList(),
    sources = state.sources.toList(),
    events = state.events.toList(),
    calendarSeries = state.calendarSeries.toList(),
    reviews = state.reviews.toList(),
    recurrences = state.recurrences.toList(),
    history = state.history.toList(),
)

/** Applies the small action set used by the mobile client to its optimistic snapshot. */
object StateProjection {
    fun apply(state: LifeState, mutation: Mutation): LifeState {
        val action = mutation.action
        val type = action.text("type") ?: return copyState(state)
        var projected = copyState(state)
        val id = action.text("id")
        when (type) {
            "capture", "create" -> {
                val title = action.text("title") ?: return projected
                val capture = type == "capture"
                val board = if (capture) {
                    projected.boards.firstOrNull { it.id == "main" }
                        ?: projected.boards.firstOrNull()
                } else {
                    projected.boards.firstOrNull { it.id == action.text("boardId") }
                }
                val boardId = action.text("boardId") ?: board?.id ?: "main"
                val columnId = action.text("columnId")
                    ?: board?.columns?.firstOrNull()?.id
                    ?: ""
                val card = Card(
                    id = "m_${mutation.id}_0",
                    title = title,
                    boardId = boardId,
                    columnId = columnId,
                    placement = if (capture) Placement.inbox else Placement.board,
                    createdAt = mutation.at,
                )
                projected = projected.copy(cards = listOf(card) + projected.cards)
            }
            "complete" -> {
                require(!id.isNullOrBlank()) { "complete requires id" }
                val done = action.boolean("done") ?: true
                projected = projected.copy(cards = projected.cards.map { card ->
                    if (card.id != id) card else card.copy(
                        done = done,
                        archived = done && (card.archived || projected.settings.autoArchiveCompleted),
                    )
                })
            }
            "update" -> {
                require(!id.isNullOrBlank()) { "update requires id" }
                val cards = projected.cards.map { card ->
                    if (card.id != id) card else card.copy(
                        title = action.text("title") ?: card.title,
                        notes = action.text("notes") ?: card.notes,
                    )
                }
                val updated = cards.firstOrNull { it.id == id }
                projected = projected.copy(
                    cards = cards,
                    boards = if (updated?.childBoardId != null) {
                        projected.boards.map { board ->
                            if (board.id == updated.childBoardId) board.copy(title = updated.title) else board
                        }
                    } else projected.boards,
                )
            }
            "schedule" -> {
                require(!id.isNullOrBlank()) { "schedule requires id" }
                val start = action.text("start") ?: return projected
                val end = action.text("end") ?: return projected
                projected = projected.copy(cards = projected.cards.map { card ->
                    if (card.id != id) card else card.copy(
                        start = start,
                        end = end,
                        placement = Placement.calendar,
                    )
                })
            }
            "move" -> {
                require(!id.isNullOrBlank()) { "move requires id" }
                val boardId = action.text("boardId") ?: return projected
                val columnId = action.text("columnId") ?: return projected
                projected = projected.copy(cards = projected.cards.map { card ->
                    if (card.id != id) card else card.copy(
                        boardId = boardId,
                        columnId = columnId,
                        placement = Placement.board,
                        start = null,
                        end = null,
                    )
                })
            }
            "inbox" -> {
                require(!id.isNullOrBlank()) { "inbox requires id" }
                projected = projected.copy(cards = projected.cards.map { card ->
                    if (card.id != id) card else card.copy(
                        placement = Placement.inbox,
                        start = null,
                        end = null,
                    )
                })
            }
            "event.create" -> {
                val title = action.text("title") ?: return projected
                val date = action.text("startDate") ?: return projected
                val allDay = action.boolean("allDay") ?: false
                val time = if (allDay) "00:00" else (action.text("startTime") ?: "09:00")
                val duration = action.text("durationMinutes")?.toIntOrNull() ?: if (allDay) 1440 else 60
                val series = EventSeries(
                    id = "m_${mutation.id}_0",
                    title = title,
                    allDay = allDay,
                    startDate = date,
                    startTime = time,
                    durationMinutes = duration,
                )
                projected = projected.copy(calendarSeries = projected.calendarSeries + series)
            }
            else -> return projected
        }
        return projected
    }
}

data class CalendarItem(
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val color: String,
    val notes: String = "",
    val location: String = "",
    val cardId: String? = null,
    val seriesId: String? = null,
    val occurrenceDate: String? = null,
    val sourceId: String = "local",
    val done: Boolean = false,
)

/** Read-only calendar view. It never writes exceptions or alters the aggregate. */
object CalendarProjection {
    val zone: ZoneId = MOSCOW

    fun items(state: LifeState, from: LocalDate, toExclusive: LocalDate): List<CalendarItem> {
        require(toExclusive.isAfter(from)) { "Calendar range must be non-empty" }
        val rangeStart = from.atStartOfDay(zone).toInstant()
        val rangeEnd = toExclusive.atStartOfDay(zone).toInstant()
        val result = mutableListOf<CalendarItem>()

        state.cards.asSequence()
            .filter { it.placement == Placement.calendar && !it.archived && !it.done }
            .forEach { card ->
                val start = parseInstant(card.start) ?: return@forEach
                val end = parseInstant(card.end) ?: return@forEach
                if (overlaps(start, end, rangeStart, rangeEnd)) {
                    result += CalendarItem(
                        id = "task:${card.id}",
                        title = card.title,
                        start = start,
                        end = end,
                        allDay = false,
                        color = state.tags.firstOrNull { it.id in card.tags }?.color ?: "#6677dd",
                        notes = card.notes,
                        cardId = card.id,
                        done = card.done,
                    )
                }
            }

        state.sources.filter { it.enabled }.forEach { source ->
            state.events.filter { it.sourceId == source.id }.forEach { event ->
                val start = parseCalendarInstant(event.start, event.allDay) ?: return@forEach
                val end = parseCalendarInstant(event.end, event.allDay) ?: return@forEach
                if (overlaps(start, end, rangeStart, rangeEnd)) {
                    result += CalendarItem(
                        id = event.id,
                        title = event.title,
                        start = start,
                        end = end,
                        allDay = event.allDay,
                        color = source.color,
                        notes = event.notes.orEmpty(),
                        location = event.location,
                        sourceId = event.sourceId,
                        done = false,
                    )
                }
            }
        }

        state.calendarSeries.forEach { series ->
            val candidates = candidateDates(series, from.minusDays(7), toExclusive)
            val seen = candidates.toMutableSet()
            candidates.forEach { date ->
                occurrence(series, date)?.let { event ->
                    appendSeriesItem(result, event, series, rangeStart, rangeEnd)
                }
            }
            // An exception can move an occurrence into the window from outside the seven-day
            // look-behind. It is still keyed by the original occurrence date.
            series.exceptions.keys.filterNot { it in seen }.forEach { date ->
                if (isOccurrenceDate(series, date)) {
                    occurrence(series, date)?.let { event ->
                        appendSeriesItem(result, event, series, rangeStart, rangeEnd)
                    }
                }
            }
        }

        return result.sortedWith(compareBy<CalendarItem> { it.start }.thenBy { it.id })
    }

    private fun appendSeriesItem(
        output: MutableList<CalendarItem>,
        event: Occurrence,
        series: EventSeries,
        rangeStart: Instant,
        rangeEnd: Instant,
    ) {
        if (overlaps(event.start, event.end, rangeStart, rangeEnd)) {
            output += CalendarItem(
                id = "local:${series.id}:${event.originalDate}",
                title = event.title,
                start = event.start,
                end = event.end,
                allDay = event.allDay,
                color = "#6677dd",
                notes = event.notes,
                location = event.location,
                seriesId = series.id,
                occurrenceDate = event.originalDate,
            )
        }
    }
}

private data class Occurrence(
    val originalDate: String,
    val start: Instant,
    val end: Instant,
    val title: String,
    val notes: String,
    val location: String,
    val allDay: Boolean,
)

private fun parseInstant(value: String?): Instant? = value?.let {
    runCatching { Instant.parse(it) }.getOrNull()
}

private fun parseCalendarInstant(value: String, allDay: Boolean): Instant? {
    parseInstant(value)?.let { return it }
    if (allDay) return runCatching { LocalDate.parse(value).atStartOfDay(MOSCOW).toInstant() }.getOrNull()
    return null
}

private fun overlaps(start: Instant, end: Instant, rangeStart: Instant, rangeEnd: Instant): Boolean =
    start.isBefore(rangeEnd) && end.isAfter(rangeStart)

private fun isOccurrenceDate(series: EventSeries, date: String): Boolean =
    runCatching { candidateDates(series, LocalDate.parse(date), LocalDate.parse(date).plusDays(1)).contains(date) }
        .getOrDefault(false)

private fun occurrence(series: EventSeries, originalDate: String): Occurrence? {
    val exception = series.exceptions[originalDate] ?: EventException()
    if (exception.cancelled == true) return null
    val date = exception.startDate ?: originalDate
    val allDay = exception.allDay ?: series.allDay
    val time = if (allDay) LocalTime.MIDNIGHT else parseTime(exception.startTime ?: series.startTime)
        ?: return null
    val duration = exception.durationMinutes ?: series.durationMinutes
    val start = runCatching { LocalDate.parse(date).atTime(time).atZone(MOSCOW).toInstant() }.getOrNull()
        ?: return null
    val end = start.plus(duration.toLong(), ChronoUnit.MINUTES)
    return Occurrence(
        originalDate = originalDate,
        start = start,
        end = end,
        title = exception.title ?: series.title,
        notes = exception.notes ?: series.notes,
        location = exception.location ?: series.location,
        allDay = allDay,
    )
}

private fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

private fun candidateDates(series: EventSeries, from: LocalDate, toExclusive: LocalDate): List<String> {
    val start = runCatching { LocalDate.parse(series.startDate) }.getOrNull() ?: return emptyList()
    if (!toExclusive.isAfter(from)) return emptyList()
    val until = series.repeat.until?.let { runCatching { LocalDate.parse(it).plusDays(1) }.getOrNull() }
    val horizon = listOfNotNull(toExclusive, until).minOrNull() ?: toExclusive
    if (!horizon.isAfter(from) || !horizon.isAfter(start)) return emptyList()
    val result = mutableListOf<LocalDate>()
    fun emit(date: LocalDate) {
        if (!date.isBefore(start) && date.isBefore(horizon) && !date.isBefore(from) && date.year <= 2199)
            result += date
    }
    val repeat = series.repeat
    val interval = repeat.interval.coerceAtLeast(1)
    when (repeat.frequency) {
        RepeatFrequency.none -> emit(start)
        RepeatFrequency.daily -> {
            val first = maxOf(0L, ChronoUnit.DAYS.between(start, from) / interval)
            var index = first
            while (true) {
                if (repeat.count != null && index >= repeat.count) break
                val date = start.plusDays(index * interval)
                if (!date.isBefore(horizon)) break
                emit(date)
                index++
            }
        }
        RepeatFrequency.weekly -> {
            val days = (repeat.weekdays ?: listOf(start.dayOfWeek.value % 7)).distinct()
                .sortedBy { (it + 6) % 7 }
            val anchor = start.minusDays(((start.dayOfWeek.value % 7 + 6) % 7).toLong())
            val first = if (repeat.count != null) 0L else
                maxOf(0L, ChronoUnit.DAYS.between(anchor, from) / (7L * interval))
            var count = 0
            var index = first
            while (true) {
                val monday = anchor.plusDays(index * 7L * interval)
                if (!monday.isBefore(horizon)) break
                for (day in days) {
                    val date = monday.plusDays(((day + 6) % 7).toLong())
                    if (date.isBefore(start)) continue
                    if (repeat.count != null && ++count > repeat.count) return result.map(LocalDate::toString)
                    emit(date)
                }
                index++
            }
        }
        RepeatFrequency.monthly, RepeatFrequency.yearly -> {
            val originalDay = start.dayOfMonth
            val anchorWeekday = start.dayOfWeek.value % 7
            val lastWeekday = originalDay + 7 > start.lengthOfMonth()
            var count = 0
            var index = 0L
            val monthStep = interval.toLong() * if (repeat.frequency == RepeatFrequency.yearly) 12 else 1
            while (true) {
                val month = start.plusMonths(index * monthStep)
                if (!month.withDayOfMonth(1).isBefore(horizon) || month.year > 2199) break
                val day = if (repeat.frequency == RepeatFrequency.monthly && repeat.monthlyMode == MonthlyMode.weekday) {
                    val firstMatch = 1 + ((anchorWeekday - (month.withDayOfMonth(1).dayOfWeek.value % 7) + 7) % 7)
                    if (lastWeekday) firstMatch + ((month.lengthOfMonth() - firstMatch) / 7) * 7
                    else firstMatch + ((originalDay - 1) / 7) * 7
                } else originalDay
                if (day <= month.lengthOfMonth()) {
                    val date = month.withDayOfMonth(day)
                    if (repeat.count != null && ++count > repeat.count) break
                    emit(date)
                }
                index++
            }
        }
    }
    return result.map(LocalDate::toString)
}
