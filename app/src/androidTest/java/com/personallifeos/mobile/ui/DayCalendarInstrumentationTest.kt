package com.personallifeos.mobile.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.debug.WidgetHostActivity
import com.personallifeos.mobile.model.Board
import com.personallifeos.mobile.model.BoardColumn
import com.personallifeos.mobile.model.CalendarEvent
import com.personallifeos.mobile.model.LifeState
import com.personallifeos.mobile.model.Source
import com.personallifeos.mobile.model.SourceKind
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused UI/device coverage for the day timeline and its day widget entry point. */
@RunWith(AndroidJUnit4::class)
class DayCalendarInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var server: MockWebServer
    private lateinit var repository: LifeRepository
    private val day = LocalDate.now(ZONE)
    private val hosts = mutableListOf<WidgetHostActivity>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = fixtureDispatcher()
        server.start()
        repository = LifeRepository.get(context)
        runBlocking {
            runCatching { repository.disconnect(discardPending = true) }
            repository.connectToken(server.url("/").toString(), "fixture-token")
        }
        openDeepLink("calendar_day", day)
    }

    @After
    fun tearDown() {
        hosts.forEach { host -> host.runOnUiThread { host.finish() } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        runCatching { runBlocking { repository.disconnect(discardPending = true) } }
        server.shutdown()
    }

    @Test
    fun dayTimelineShowsSelectedDateAllDayAndOverlappingTimedEvents() {
        awaitDayTimeline()
        composeRule.onNodeWithText(day.format(DAY_TITLE)).assertExists()
        composeRule.onNodeWithTag("day_event_$ALL_DAY_ID").assertExists()
        composeRule.onNodeWithText(FIXTURE_ALL_DAY).assertExists()
        composeRule.onNodeWithTag("day_event_$TIMED_ONE_ID").assertExists()
        composeRule.onNodeWithTag("day_event_$TIMED_TWO_ID").assertExists()
        composeRule.onNodeWithText(FIXTURE_TIMED_ONE).assertExists()
        composeRule.onNodeWithText(FIXTURE_TIMED_TWO).assertExists()
    }

    @Test
    fun clickingEmptyHourPrefillsEventCreationTime() {
        awaitDayTimeline()
        composeRule.onNodeWithTag("day_hour_9").performScrollTo()
        composeRule.onNodeWithTag("day_hour_9").performTouchInput {
            // The hour row contains a labeled time column and an empty grid cell.
            click(position = androidx.compose.ui.geometry.Offset(width * .8f, height / 3f))
        }
        composeRule.onNodeWithText("Новое событие").assertExists()
        composeRule.onNode(hasSetTextAction() and hasText("09:15")).assertExists()
        composeRule.onNodeWithText(day.toString()).assertExists()
    }

    @Test
    fun previousNextTodayAndDayWidgetDeepLinkLeaveMonthModeOnSelectedDay() {
        awaitDayTimeline()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithText(day.minusDays(1).format(DAY_TITLE)).assertExists()
        composeRule.onNodeWithContentDescription("Вперёд").performClick()
        composeRule.onNodeWithText(day.format(DAY_TITLE)).assertExists()
        composeRule.onNodeWithText("Сегодня").performClick()
        composeRule.onNodeWithText(day.format(DAY_TITLE)).assertExists()

        composeRule.onNodeWithText("Месяц").performClick()
        val host = InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, WidgetHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("provider", "day"),
        ) as WidgetHostActivity
        hosts += host
        check(device.wait(Until.hasObject(By.desc("Открыть день")), WAIT_MS)) {
            "Day widget did not render its day deep-link header"
        }
        device.findObject(By.desc("Открыть день")).click()
        awaitDayTimeline()
        composeRule.onNodeWithText(day.format(DAY_TITLE)).assertExists()
        device.pressBack()
    }

    private fun openDeepLink(screen: String, date: LocalDate, time: String? = null) {
        val intent = Intent(composeRule.activity.intent)
            .setClass(context, MainActivity::class.java)
            .putExtra("screen", screen)
            .putExtra("date", date.toString())
        time?.let { intent.putExtra("time", it) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(composeRule.activity, intent)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitDayTimeline() {
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag("day_timeline").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun fixtureDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "GET" && request.path?.startsWith("/api/state") == true) {
                require(request.getHeader("Authorization") == "Bearer fixture-token")
                val path = requireNotNull(request.path)
                return if (path.contains("?mutations=")) {
                    response("{\"state\":${json.encodeToString(fixtureState())},\"applied\":[]}")
                } else {
                    response(json.encodeToString(fixtureState()))
                }
            }
            return MockResponse().setResponseCode(404)
        }
    }

    private fun response(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixtureState(): LifeState {
        val first = day.atTime(LocalTime.of(9, 30)).atZone(ZONE).toInstant()
        val second = day.atTime(LocalTime.of(10, 0)).atZone(ZONE).toInstant()
        return LifeState(
            revision = 0,
            boards = listOf(Board("main", "Основная", columns = listOf(BoardColumn("inbox", "Входящие")))),
            sources = listOf(Source("fixture", "Тестовый календарь", enabled = true, kind = SourceKind.file)),
            events = listOf(
                CalendarEvent(ALL_DAY_ID, sourceId = "fixture", title = FIXTURE_ALL_DAY, start = day.toString(), end = day.plusDays(1).toString(), allDay = true),
                CalendarEvent(TIMED_ONE_ID, sourceId = "fixture", title = FIXTURE_TIMED_ONE, start = first.toString(), end = first.plusSeconds(90 * 60).toString()),
                CalendarEvent(TIMED_TWO_ID, sourceId = "fixture", title = FIXTURE_TIMED_TWO, start = second.toString(), end = second.plusSeconds(60 * 60).toString()),
            ),
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
        val DAY_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
        const val WAIT_MS = 15_000L
        const val ALL_DAY_ID = "fixture-day-all-day"
        const val TIMED_ONE_ID = "fixture-day-timed-one"
        const val TIMED_TWO_ID = "fixture-day-timed-two"
        const val FIXTURE_ALL_DAY = "День рождения команды"
        const val FIXTURE_TIMED_ONE = "Утренний фокус"
        const val FIXTURE_TIMED_TWO = "Перекрывающаяся встреча"
    }
}
