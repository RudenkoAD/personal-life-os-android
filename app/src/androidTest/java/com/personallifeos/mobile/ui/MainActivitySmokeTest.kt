package com.personallifeos.mobile.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.model.Board
import com.personallifeos.mobile.model.BoardColumn
import com.personallifeos.mobile.model.LifeState
import com.personallifeos.mobile.model.Mutation
import com.personallifeos.mobile.model.StateProjection
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Login, fast capture, and optimistic completion through the real Compose entry point. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val json = Json { encodeDefaults = true }
    private lateinit var server: MockWebServer
    private lateinit var repository: LifeRepository
    @Volatile private var currentState = fixtureState()
    private val queriedMutationIds = mutableListOf<String>()
    private val postedMutationIds = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = fixtureDispatcher()
        server.start()
        repository = LifeRepository.get(context)
        currentState = fixtureState()
        queriedMutationIds.clear()
        postedMutationIds.clear()
        runBlocking { runCatching { repository.disconnect(discardPending = true) } }
    }

    @After
    fun tearDown() {
        runCatching { runBlocking { repository.disconnect(discardPending = true) } }
        server.shutdown()
    }

    @Test
    fun loginCaptureAndCompleteRemainLocalBeforeFixtureRoundTrip() {
        val origin = server.url("/").toString()

        // Login screen starts with Origin and password. Open the collapsed token form and fill
        // the newly visible field; no real password or service token is involved.
        composeRule.onNodeWithTag("login_origin").performTextReplacement(origin)
        composeRule.onNodeWithText("Токен").performClick()
        composeRule.onNodeWithTag("login_token").performTextReplacement("fixture-token")
        composeRule.onNodeWithText("Войти по токену").performClick()
        composeRule.waitForText("Входящие пусты")

        composeRule.onNodeWithTag("inbox_add").performClick()
        composeRule.onNodeWithTag("capture_title").performTextReplacement("UI fixture task")
        composeRule.onNodeWithTag("capture_save").performClick()
        composeRule.waitForText("UI fixture task")

        composeRule.onNodeWithContentDescription("Завершить UI fixture task").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("UI fixture task").fetchSemanticsNodes().isEmpty()
        }
        runBlocking { repository.sync() }
        composeRule.runOnIdle {
            check(postedMutationIds.size >= 2) { "capture and complete must both reach the fixture" }
            check(queriedMutationIds.size >= 2) { "each queued mutation must be announced in GET /api/state" }
        }
    }

    private fun fixtureDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.method == "GET" && request.path?.startsWith("/api/state") == true ->
                stateResponse(request)

            request.method == "POST" && request.path == "/api/actions" -> {
                require(request.getHeader("Authorization") == "Bearer fixture-token")
                val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val requestRevision = body["revision"]?.jsonPrimitive?.long
                    ?: error("fixture request has no revision")
                val mutationElement = body["mutation"] ?: error("fixture request has no mutation")
                val mutation = json.decodeFromJsonElement(Mutation.serializer(), mutationElement)
                check(requestRevision == currentState.revision) {
                    "expected revision ${currentState.revision}, got $requestRevision"
                }
                check(mutation.id in queriedMutationIds) {
                    "POST receipt ${mutation.id} was not announced by GET /api/state"
                }
                postedMutationIds += mutation.id
                currentState = StateProjection.apply(currentState, mutation)
                    .copy(revision = currentState.revision + 1)
                response(json.encodeToString(currentState))
            }

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

    private fun response(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixtureState() = LifeState(
        revision = 0,
        boards = listOf(Board("main", "Основная", columns = listOf(BoardColumn("inbox", "Входящие")))),
    )
}

private fun ComposeTestRule.waitForText(value: String) {
    waitUntil(10_000) { onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
}
