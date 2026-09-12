package com.personallifeos.mobile.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectionTest {
    private val mutationId = "123e4567-e89b-42d3-a456-426614174000"
    private val oracleInstant = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
        .withZone(ZoneOffset.UTC)

    @Test
    fun actionsUseServiceFieldNamesAndMillisecondInstants() {
        val action = Actions.schedule(
            "task-1",
            Instant.parse("2026-09-12T10:00:00Z"),
            Instant.parse("2026-09-12T11:15:00.125Z"),
        )
        assertEquals("schedule", action["type"]?.toString()?.trim('"'))
        assertEquals("2026-09-12T10:00:00.000Z", action["start"]?.toString()?.trim('"'))
        assertEquals("2026-09-12T11:15:00.125Z", action["end"]?.toString()?.trim('"'))

        val event = Actions.eventCreate("Birthday", LocalDate.of(2026, 9, 12), LocalTime.of(9, 30), 1440, true)
        assertEquals("event.create", event["type"]?.toString()?.trim('"'))
        assertEquals("00:00", event["startTime"]?.toString()?.trim('"'))
    }

    @Test
    fun actionsValidateAndNormalizeBeforeQueueing() {
        assertEquals("Title", Actions.capture("  Title ")["title"]?.toString()?.trim('"'))
        assertEquals("Title", Actions.update("id", "  Title ", "  notes  ")["title"]?.toString()?.trim('"'))
        assertEquals("notes", Actions.update("id", "Title", "  notes  ")["notes"]?.toString()?.trim('"'))
        assertThrows(IllegalArgumentException::class.java) { Actions.capture(" ") }
        assertThrows(IllegalArgumentException::class.java) { Actions.capture("x".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { Actions.update("id", "Title", "x".repeat(8001)) }
        assertThrows(IllegalArgumentException::class.java) {
            Actions.schedule("id", Instant.parse("2026-09-12T10:00:00Z"), Instant.parse("2026-09-19T10:00:00.001Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Actions.eventCreate("Event", LocalDate.of(1899, 12, 31), LocalTime.NOON, 60)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Actions.eventCreate("Event", LocalDate.of(2026, 9, 12), LocalTime.NOON, 16)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Actions.eventCreate("Event", LocalDate.of(2026, 9, 12), LocalTime.NOON, 60, allDay = true)
        }
    }

    @Test
    fun captureAndCompletionAreDeterministicAndRespectArchiveSetting() {
        val state = LifeState(
            settings = Settings(autoArchiveCompleted = true),
            boards = listOf(Board("main", "Main", columns = listOf(BoardColumn("next", "Next")))),
        )
        val mutation = Mutation(mutationId, "2026-09-12T10:00:00.000Z", Actions.capture("Inbox item"))
        val captured = StateProjection.apply(state, mutation)
        assertEquals("m_${mutationId}_0", captured.cards.single().id)
        assertEquals(Placement.inbox, captured.cards.single().placement)
        assertEquals("main", captured.cards.single().boardId)
        assertEquals("next", captured.cards.single().columnId)
        assertTrue(state.cards.isEmpty())

        val completed = StateProjection.apply(captured, Mutation(
            "223e4567-e89b-42d3-a456-426614174000",
            "2026-09-12T10:01:00.000Z",
            Actions.complete(captured.cards.single().id),
        ))
        assertTrue(completed.cards.single().done)
        assertTrue(completed.cards.single().archived)
    }

    @Test
    fun createProjectsCardOnSelectedBoardAndColumn() {
        val state = LifeState(
            boards = listOf(Board("main", "Main", columns = listOf(BoardColumn("next", "Next")))),
        )
        val action = Actions.create("Planned", "main", "next")
        val projected = StateProjection.apply(
            state,
            Mutation(mutationId, "2026-09-12T10:00:00.000Z", action),
        )
        assertEquals(Placement.board, projected.cards.single().placement)
        assertEquals("main", projected.cards.single().boardId)
        assertEquals("next", projected.cards.single().columnId)
    }

    @Test
    fun inboxReturnsScheduledCardAndClearsItsWindow() {
        val card = Card(
            id = "scheduled",
            title = "Scheduled",
            placement = Placement.calendar,
            start = "2026-09-12T07:00:00.000Z",
            end = "2026-09-12T08:00:00.000Z",
        )
        val projected = StateProjection.apply(
            LifeState(cards = listOf(card)),
            Mutation(
                mutationId,
                "2026-09-12T10:00:00.000Z",
                Actions.inbox(card.id),
            ),
        )
        assertEquals(Placement.inbox, projected.cards.single().placement)
        assertEquals(null, projected.cards.single().start)
        assertEquals(null, projected.cards.single().end)
    }

    @Test
    fun calendarExpansionUsesMoscowAndExceptionsWithoutMutation() {
        val series = EventSeries(
            id = "series-1",
            title = "Daily",
            startDate = "2026-09-12",
            startTime = "10:00",
            durationMinutes = 60,
            repeat = EventRepeat(RepeatFrequency.daily, interval = 1),
            exceptions = mapOf(
                "2026-09-13" to EventException(cancelled = true),
                "2026-09-14" to EventException(title = "Moved", startTime = "23:00"),
            ),
        )
        val state = LifeState(calendarSeries = listOf(series))
        val before = state.calendarSeries
        val items = CalendarProjection.items(state, LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-16"))
        assertEquals(listOf("2026-09-12", "2026-09-14", "2026-09-15"), items.map { it.occurrenceDate })
        assertEquals(Instant.parse("2026-09-12T07:00:00Z"), items.first().start)
        assertEquals("Moved", items[1].title)
        assertEquals(before, state.calendarSeries)
    }

    @Test
    fun halfOpenRangeIncludesOverlapsAndOnlyEnabledSources() {
        val state = LifeState(
            sources = listOf(
                Source("on", "On", "#112233", enabled = true),
                Source("off", "Off", "#445566", enabled = false),
            ),
            events = listOf(
                CalendarEvent("e1", "on", "u1", "Overnight", "2026-09-11T21:00:00Z", "2026-09-12T08:00:00Z"),
                CalendarEvent("e2", "on", "u2", "At end", "2026-09-13T00:00:00Z", "2026-09-13T01:00:00Z"),
                CalendarEvent("e3", "off", "u3", "Hidden", "2026-09-12T10:00:00Z", "2026-09-12T11:00:00Z"),
            ),
        )
        val items = CalendarProjection.items(state, LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-13"))
        assertEquals(listOf("e1"), items.map { it.id })
        assertFalse(items.any { it.title == "Hidden" })
    }

    @Test
    fun recurrenceProjectionMatchesServiceOracleFixtures() {
        val json = Json { ignoreUnknownKeys = true }
        val stream = checkNotNull(javaClass.getResourceAsStream("/calendar-oracle.json"))
        val fixtures = stream.bufferedReader().use { reader ->
            json.decodeFromString<List<OracleCase>>(reader.readText())
        }
        fixtures.forEach { fixture ->
            val state = LifeState(calendarSeries = fixture.series)
            val actual = CalendarProjection.items(
                state,
                LocalDate.parse(fixture.from),
                LocalDate.parse(fixture.to),
            ).map { item ->
                OracleItem(item.id, item.title, oracleInstant.format(item.start), oracleInstant.format(item.end), item.allDay, item.occurrenceDate)
            }
            assertEquals("fixture ${fixture.name}", fixture.expected, actual)
        }
    }
}

@Serializable
private data class OracleCase(
    val name: String,
    val from: String,
    val to: String,
    val series: List<EventSeries>,
    val expected: List<OracleItem>,
)

@Serializable
private data class OracleItem(
    val id: String,
    val title: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val occurrenceDate: String? = null,
)
