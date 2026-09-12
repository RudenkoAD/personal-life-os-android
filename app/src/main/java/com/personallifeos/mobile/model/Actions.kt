package com.personallifeos.mobile.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON actions accepted by the service. Keep this list deliberately small: synthetic
 * client fields must never be sent to /api/actions. */
object Actions {
    private val instantFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
        .withZone(ZoneOffset.UTC)

    private fun instant(value: Instant): String = instantFormatter.format(value)

    private fun title(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200) {
            "Название: от 1 до 200 символов"
        }
        return trimmed
    }

    private fun notes(value: String): String {
        require(value.length <= 8000) { "Заметки: до 8000 символов" }
        return value.trim()
    }

    fun capture(title: String): JsonObject = buildJsonObject {
        put("type", "capture")
        put("title", title(title))
    }

    fun create(title: String, boardId: String, columnId: String): JsonObject = buildJsonObject {
        put("type", "create")
        put("title", title(title))
        put("boardId", boardId)
        put("columnId", columnId)
    }

    fun complete(id: String, done: Boolean = true): JsonObject = buildJsonObject {
        put("type", "complete")
        put("id", id)
        put("done", done)
    }

    fun update(id: String, title: String, notes: String): JsonObject = buildJsonObject {
        put("type", "update")
        put("id", id)
        put("title", title(title))
        put("notes", notes(notes))
    }

    fun schedule(id: String, start: Instant, end: Instant): JsonObject {
        require(end.isAfter(start) && end <= start.plusSeconds(7L * 24 * 60 * 60)) {
            "Укажите корректное время начала и окончания (до 7 дней)"
        }
        return buildJsonObject {
            put("type", "schedule")
            put("id", id)
            put("start", instant(start))
            put("end", instant(end))
        }
    }

    fun move(id: String, boardId: String, columnId: String): JsonObject = buildJsonObject {
        put("type", "move")
        put("id", id)
        put("boardId", boardId)
        put("columnId", columnId)
    }

    fun inbox(id: String): JsonObject = buildJsonObject {
        put("type", "inbox")
        put("id", id)
    }

    fun eventCreate(
        title: String,
        date: LocalDate,
        time: LocalTime,
        durationMinutes: Int,
        allDay: Boolean = false,
    ): JsonObject {
        require(date.year in 1900..2199) { "Дата должна быть между 1900 и 2199 годом" }
        if (allDay) {
            require(durationMinutes in 1440..10080 && durationMinutes % 1440 == 0) {
                "Длительность: от 1 до 7 целых дней"
            }
        } else {
            require(durationMinutes in 15..10080 && durationMinutes % 15 == 0) {
                "Длительность: от 15 минут до 7 дней, шаг 15 минут"
            }
        }
        return buildJsonObject {
            put("type", "event.create")
            put("title", title(title))
            put("startDate", date.toString())
            // The service contract accepts HH:mm. An all-day event is normalized to midnight.
            put("startTime", if (allDay) "00:00" else "%02d:%02d".format(time.hour, time.minute))
            put("durationMinutes", durationMinutes)
            put("allDay", allDay)
        }
    }
}
