package com.personallifeos.mobile.debug

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.TextView
import com.personallifeos.mobile.data.LifeRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Seeds only the device-local calendar used by the widget comparison fixture.
 *
 * This activity intentionally requires permissions to have been granted by adb
 * beforehand. It does not request access and never touches another calendar.
 */
class ComparisonCalendarActivity : Activity() {
    private val zone = ZoneId.of("Europe/Moscow")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = if (!hasCalendarPermissions()) {
            "Calendar permissions required: grant READ_CALENDAR and WRITE_CALENDAR first."
        } else {
            try {
                ensureSyntheticAccount()
                val count = seedCalendar(contentResolver)
                "Seeded $count synthetic calendar events."
            } catch (_: SecurityException) {
                "Calendar permissions required: grant READ_CALENDAR and WRITE_CALENDAR first."
            } catch (_: IllegalArgumentException) {
                "Could not seed the synthetic calendar."
            }
        }
        val messageView = TextView(this).apply {
            text = message
            setPadding(32, 32, 32, 32)
        }
        setContentView(messageView)

        if (intent.getBooleanExtra("connectLifeOs", false) && message.startsWith("Seeded ")) {
            scope.launch {
                try {
                    LifeRepository.get(applicationContext).connectToken(FIXTURE_ORIGIN, FIXTURE_TOKEN)
                    messageView.text = "$message Life OS connected."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    messageView.text = "$message Life OS connection failed."
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun hasCalendarPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun seedCalendar(resolver: ContentResolver): Int {
        val calendarId = findOrCreateOwnedCalendar(resolver)

        // Idempotency is scoped to this one calendar; no other calendar/event row is queried or changed.
        resolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CALENDAR_ID}=?",
            arrayOf(calendarId.toString()),
        )

        val today = LocalDate.now(zone)
        val scheduledStart = today.atTime(9, 30).atZone(zone)
        val overlapStart = today.atTime(10, 15).atZone(zone)
        val allDayStartUtc = today.atStartOfDay(ZoneOffset.UTC)
        val nextDayUtc = today.plusDays(1).atStartOfDay(ZoneOffset.UTC)
        val recurringStart = today.atTime(12, 30).atZone(zone)

        val events = listOf(
            eventValues(
                calendarId = calendarId,
                title = "Scheduled focus session",
                startMillis = scheduledStart.toInstant().toEpochMilli(),
                endMillis = scheduledStart.plusHours(1).toInstant().toEpochMilli(),
            ),
            eventValues(
                calendarId = calendarId,
                title = "Design review",
                startMillis = overlapStart.toInstant().toEpochMilli(),
                endMillis = overlapStart.plusMinutes(75).toInstant().toEpochMilli(),
            ),
            eventValues(
                calendarId = calendarId,
                title = "Birthday",
                startMillis = allDayStartUtc.toInstant().toEpochMilli(),
                endMillis = nextDayUtc.toInstant().toEpochMilli(),
                allDay = true,
                eventTimezone = "UTC",
            ),
            eventValues(
                calendarId = calendarId,
                title = "Weekly review",
                startMillis = recurringStart.toInstant().toEpochMilli(),
                endMillis = recurringStart.plusMinutes(30).toInstant().toEpochMilli(),
                eventTimezone = zone.id,
                rrule = "FREQ=WEEKLY;INTERVAL=1",
            ),
        )
        val insertedIds = events.map { values ->
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
                ?: error("CalendarProvider did not insert a synthetic event")
        }
        return insertedIds.size
    }

    private fun findOrCreateOwnedCalendar(resolver: ContentResolver): Long {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE),
            "${CalendarContract.Calendars._ID} ASC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0).also { updateOwnedCalendar(resolver, it) }
            }
        }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Widget comparison")
            put(CalendarContract.Calendars.CALENDAR_COLOR, Color.rgb(63, 81, 181))
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
        }
        // CalendarProvider requires local-calendar creation through the sync-adapter URI.
        val uri = resolver.insert(localCalendarSyncUri(), values)
            ?: error("CalendarProvider did not create the local calendar")
        return uri.lastPathSegment?.toLongOrNull()
            ?: error("CalendarProvider returned an invalid calendar id")
    }

    private fun updateOwnedCalendar(resolver: ContentResolver, calendarId: Long) {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Widget comparison")
            put(CalendarContract.Calendars.CALENDAR_COLOR, Color.rgb(63, 81, 181))
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        resolver.update(ContentUris.withAppendedId(localCalendarSyncUri(), calendarId), values, null, null)
    }

    private fun eventValues(
        calendarId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean = false,
        eventTimezone: String = zone.id,
        rrule: String? = null,
    ) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, startMillis)
        if (rrule == null) {
            put(CalendarContract.Events.DTEND, endMillis)
        } else {
            put(CalendarContract.Events.DURATION, "PT${(endMillis - startMillis) / 60_000}M")
        }
        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, eventTimezone)
        put(CalendarContract.Events.EVENT_END_TIMEZONE, eventTimezone)
        put(CalendarContract.Events.HAS_ALARM, 0)
        if (rrule != null) put(CalendarContract.Events.RRULE, rrule)
    }

    private fun localCalendarSyncUri() = CalendarContract.Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(
            CalendarContract.Calendars.ACCOUNT_TYPE,
            ACCOUNT_TYPE,
        )
        .build()

    private companion object {
        const val ACCOUNT_NAME = "lifeos-widget-comparison"
        const val ACCOUNT_TYPE = "com.personallifeos.mobile.debug.widgetcomparison"
        const val FIXTURE_ORIGIN = "http://127.0.0.1:18765"
        const val FIXTURE_TOKEN = "fixture-token"
        const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"
        const val CALENDAR_AUTHORITY = "com.android.calendar"
    }

    private fun ensureSyntheticAccount() {
        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
        val accountManager = AccountManager.get(this)
        accountManager.addAccountExplicitly(account, null, null)
        accountManager.setAccountVisibility(
            account,
            GOOGLE_CALENDAR_PACKAGE,
            AccountManager.VISIBILITY_VISIBLE,
        )
        ContentResolver.setIsSyncable(account, CALENDAR_AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, CALENDAR_AUTHORITY, true)
    }

}
