package com.personallifeos.mobile.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class CardType { task, sequence, project }

@Serializable
enum class Placement { inbox, board, calendar }

@Serializable
data class Step(
    val id: String = "",
    val title: String = "",
    val done: Boolean = false,
)

@Serializable
data class Card(
    val id: String = "",
    val title: String = "",
    val notes: String = "",
    val type: CardType = CardType.task,
    val placement: Placement = Placement.inbox,
    val boardId: String = "main",
    val columnId: String = "",
    val childBoardId: String? = null,
    val tags: List<String> = emptyList(),
    val steps: List<Step> = emptyList(),
    val done: Boolean = false,
    val start: String? = null,
    val end: String? = null,
    val archived: Boolean = false,
    val createdAt: String = "",
    val recurrenceId: String? = null,
)

@Serializable
data class BoardColumn(
    val id: String = "",
    val title: String = "",
)

@Serializable
data class Board(
    val id: String = "",
    val title: String = "",
    val parentCardId: String? = null,
    val columns: List<BoardColumn> = emptyList(),
)

@Serializable
data class Scope(
    val id: String = "",
    val title: String = "",
    val color: String = "#6677dd",
)

@Serializable
data class Settings(
    val autoArchiveCompleted: Boolean = true,
)

@Serializable
data class ReviewHistory(
    val date: String = "",
    val notes: String = "",
)

@Serializable
data class Review(
    val id: String = "",
    val title: String = "",
    val prompts: List<Step> = emptyList(),
    val intervalDays: Int = 30,
    val nextDue: String = "",
    val notes: String = "",
    val history: List<ReviewHistory> = emptyList(),
)

@Serializable
data class RecurringRule(
    val id: String = "",
    val title: String = "",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val intervalMinutes: Int = 1440,
    val firstAt: String = "",
    val nextAt: String? = null,
    val waitingCardId: String? = null,
    val generation: Int = 0,
    val lastReleasedAt: String? = null,
)

@Serializable
enum class SourceKind { file, feed, caldav }

@Serializable
data class Source(
    val id: String = "",
    val title: String = "",
    val color: String = "#6677dd",
    val enabled: Boolean = false,
    val kind: SourceKind = SourceKind.file,
    val tags: List<String> = emptyList(),
    val lastSynced: String = "",
    val error: String? = null,
)

@Serializable
data class CalendarEvent(
    val id: String = "",
    val sourceId: String = "",
    val uid: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = "",
    val allDay: Boolean = false,
    val tags: List<String> = emptyList(),
    val location: String = "",
    val notes: String? = null,
    val seriesId: String? = null,
    val occurrenceDate: String? = null,
)

@Serializable
enum class RepeatFrequency { none, daily, weekly, monthly, yearly }

@Serializable
data class EventRepeat(
    val frequency: RepeatFrequency = RepeatFrequency.none,
    val interval: Int = 1,
    val weekdays: List<Int>? = null,
    val monthlyMode: MonthlyMode? = null,
    val until: String? = null,
    val count: Int? = null,
)

@Serializable
enum class MonthlyMode { date, weekday }

@Serializable
data class EventException(
    val title: String? = null,
    val notes: String? = null,
    val location: String? = null,
    val tags: List<String>? = null,
    val allDay: Boolean? = null,
    val startDate: String? = null,
    val startTime: String? = null,
    val durationMinutes: Int? = null,
    val cancelled: Boolean? = null,
)

@Serializable
data class EventSeries(
    val id: String = "",
    val title: String = "",
    val notes: String = "",
    val location: String = "",
    val tags: List<String> = emptyList(),
    val allDay: Boolean = false,
    val startDate: String = "",
    val startTime: String = "00:00",
    val durationMinutes: Int = 60,
    val repeat: EventRepeat = EventRepeat(),
    val exceptions: Map<String, EventException> = emptyMap(),
)

@Serializable
data class HistoryEntry(
    val at: String = "",
    val text: String = "",
    val actor: String = "",
)

@Serializable
data class LifeState(
    val revision: Long = 0,
    val settings: Settings = Settings(),
    val cards: List<Card> = emptyList(),
    val boards: List<Board> = emptyList(),
    val tags: List<Scope> = emptyList(),
    val sources: List<Source> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val calendarSeries: List<EventSeries> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val recurrences: List<RecurringRule> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
)

@Serializable
data class Mutation(
    val id: String,
    val at: String,
    val action: JsonObject,
)
