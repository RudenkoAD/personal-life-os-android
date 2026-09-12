package com.personallifeos.mobile.widgets

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
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
import com.personallifeos.mobile.model.Card
import com.personallifeos.mobile.model.EventRepeat
import com.personallifeos.mobile.model.EventSeries
import com.personallifeos.mobile.model.LifeState
import com.personallifeos.mobile.model.Mutation
import com.personallifeos.mobile.model.Placement
import com.personallifeos.mobile.model.RepeatFrequency
import com.personallifeos.mobile.model.StateProjection
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke for actual Glance -> RemoteViews output.
 *
 * The host activity is debug-only. The test runner must grant widget binding before execution:
 * `adb shell appwidget grantbind --package com.personallifeos.mobile.debug --user 0`.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer
    private lateinit var repository: LifeRepository
    private lateinit var fixture: LifeState
    private val queriedMutationIds = ConcurrentLinkedQueue<String>()
    private val postedMutationIds = ConcurrentLinkedQueue<String>()
    @Volatile private var currentState = LifeState()
    private val hosts = mutableListOf<ActivityScenario<WidgetHostActivity>>()

    @Before
    fun setUp() {
        fixture = fixtureState()
        currentState = fixture
        queriedMutationIds.clear()
        postedMutationIds.clear()
        server = MockWebServer()
        server.dispatcher = fixtureDispatcher()
        server.start()
        repository = LifeRepository.get(context)
        runBlocking {
            runCatching { repository.disconnect(discardPending = true) }
            repository.connectToken(server.url("/").toString(), "fixture-token")
        }
    }

    @After
    fun tearDown() {
        hosts.forEach { it.close() }
        runCatching { runBlocking { repository.disconnect(discardPending = true) } }
        server.shutdown()
    }

    @Test
    fun inboxAgendaAndMonthRenderFixtureAndInboxCallbackCompletesCard() {
        val inbox = launchHost("inbox")
        awaitText(FIXTURE_INBOX_LONG)
        awaitText(FIXTURE_INBOX_SECOND)
        screenshot("widget-inbox.png")

        val completionSelector = By.desc("Завершить: $FIXTURE_INBOX_LONG")
        check(device.wait(Until.hasObject(completionSelector), WAIT_MS)) {
            "Inbox checkbox content description was not rendered"
        }
        val complete = device.findObject(completionSelector)
        complete.click()
        check(device.wait(Until.gone(By.text(FIXTURE_INBOX_LONG)), WAIT_MS)) {
            "Inbox callback did not remove the completed task from the cached widget"
        }
        check(runBlocking {
            repository.snapshot().state?.cards?.firstOrNull { it.id == "fixture-inbox-1" }?.done == true
        }) { "Inbox callback did not project completion into the repository snapshot" }
        runBlocking { repository.sync() }
        check(postedMutationIds.isNotEmpty()) { "Inbox callback did not reach the fixture server" }
        screenshot("widget-inbox-completed.png")
        inbox.close()

        val agenda = launchHost("agenda")
        awaitText(FIXTURE_SCHEDULED)
        awaitText(FIXTURE_RECURRING)
        screenshot("widget-agenda.png")
        agenda.close()

        val month = launchHost("month")
        val today = LocalDate.now(ZONE)
        val currentMonthTitle = monthTitle(YearMonth.from(today))
        val nextMonthTitle = monthTitle(YearMonth.from(today).plusMonths(1))
        awaitText(currentMonthTitle)
        check(device.wait(Until.hasObject(By.descContains("Открыть ${today.dayOfMonth}")), WAIT_MS)) {
            "Month widget did not render today's grid cell"
        }
        screenshot("widget-month.png")

        val nextMonth = By.desc("Следующий месяц")
        check(device.wait(Until.hasObject(nextMonth), WAIT_MS)) { "Month next control was not rendered" }
        device.findObject(nextMonth).click()
        awaitText(nextMonthTitle)
        check(device.wait(Until.gone(By.text(currentMonthTitle)), WAIT_MS)) {
            "Month widget kept the stale month title after navigation"
        }

        val todayControl = By.desc("Перейти к текущему месяцу")
        check(device.wait(Until.hasObject(todayControl), WAIT_MS)) { "Month today control was not rendered" }
        device.findObject(todayControl).click()
        awaitText(currentMonthTitle)
        month.close()

        check(queriedMutationIds.isNotEmpty()) { "Fixture never received a mutation receipt query" }
    }

    private fun launchHost(provider: String): ActivityScenario<WidgetHostActivity> {
        val scenario = ActivityScenario.launch<WidgetHostActivity>(
            Intent(context, WidgetHostActivity::class.java).putExtra("provider", provider),
        )
        hosts += scenario
        return scenario
    }

    private fun awaitText(text: String) {
        check(device.wait(Until.hasObject(By.text(text)), WAIT_MS)) {
            "Widget did not render expected text: $text"
        }
    }

    private fun screenshot(name: String) {
        val directory = requireNotNull(context.getExternalFilesDir(null)).apply { mkdirs() }
        val file = File(directory, name)
        check(device.takeScreenshot(file)) { "Could not save widget screenshot: $file" }
        println("Widget smoke screenshot: $file")
    }

    private fun fixtureDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.method == "GET" && request.path?.startsWith("/api/state") == true -> stateResponse(request)
            request.method == "POST" && request.path == "/api/actions" -> postResponse(request)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun stateResponse(request: RecordedRequest): MockResponse {
        require(request.getHeader("Authorization") == "Bearer fixture-token")
        val path = requireNotNull(request.path)
        return if (path.contains("?mutations=")) {
            queriedMutationIds += path.substringAfter("?mutations=").split(',').filter { it.isNotBlank() }
            response("{\"state\":${json.encodeToString(currentState)},\"applied\":[]}")
        } else {
            response(json.encodeToString(currentState))
        }
    }

    private fun postResponse(request: RecordedRequest): MockResponse {
        require(request.getHeader("Authorization") == "Bearer fixture-token")
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val revision = body["revision"]?.jsonPrimitive?.long ?: error("missing fixture revision")
        check(revision == currentState.revision) { "expected ${currentState.revision}, got $revision" }
        val mutation = json.decodeFromJsonElement(
            Mutation.serializer(),
            body["mutation"] ?: error("missing fixture mutation"),
        )
        check(mutation.id in queriedMutationIds) { "mutation id missing from state receipt query" }
        // Retries after an uncertain response must keep this exact id.
        postedMutationIds += mutation.id
        currentState = StateProjection.apply(currentState, mutation)
            .copy(revision = currentState.revision + 1)
        return response(json.encodeToString(currentState))
    }

    private fun response(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun monthTitle(month: YearMonth): String = DateTimeFormatter
        .ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))
        .format(month.atDay(1))
        .replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }

    private fun fixtureState(): LifeState {
        val today = LocalDate.now(ZONE)
        val scheduledStart = today.atTime(LocalTime.of(9, 30)).atZone(ZONE).toInstant()
        return LifeState(
            revision = 0,
            boards = listOf(Board("main", "Основная", columns = listOf(BoardColumn("inbox", "Входящие")))),
            cards = listOf(
                Card("fixture-inbox-1", FIXTURE_INBOX_LONG, placement = Placement.inbox, boardId = "main", columnId = "inbox"),
                Card("fixture-inbox-2", FIXTURE_INBOX_SECOND, placement = Placement.inbox, boardId = "main", columnId = "inbox"),
                Card(
                    "fixture-calendar-task",
                    FIXTURE_SCHEDULED,
                    placement = Placement.calendar,
                    boardId = "main",
                    columnId = "inbox",
                    start = scheduledStart.toString(),
                    end = scheduledStart.plusSeconds(3600).toString(),
                ),
            ),
            calendarSeries = listOf(
                EventSeries(
                    id = "fixture-series",
                    title = FIXTURE_RECURRING,
                    startDate = today.toString(),
                    startTime = "12:30",
                    durationMinutes = 30,
                    repeat = EventRepeat(RepeatFrequency.none),
                ),
            ),
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
        const val WAIT_MS = 15_000L
        const val FIXTURE_INBOX_LONG = "Очень длинная русская задача для проверки переноса строк в виджете"
        const val FIXTURE_INBOX_SECOND = "Вторая задача Inbox"
        const val FIXTURE_SCHEDULED = "Запланированная задача сегодня"
        const val FIXTURE_RECURRING = "Повторяющееся событие сегодня"
    }
}
