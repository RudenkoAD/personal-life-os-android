package com.personallifeos.mobile.data

import com.personallifeos.mobile.model.Actions
import com.personallifeos.mobile.model.Card
import com.personallifeos.mobile.model.LifeState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
class LifeRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: LifeRepository
    private lateinit var memoryStore: MemoryStore
    private val wireJson = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        memoryStore = MemoryStore()
        repository = LifeRepository.forTests(
            RuntimeEnvironment.getApplication(),
            OkHttpClient.Builder().followRedirects(false).build(),
            memoryStore,
        )
        runBlocking { repository.disconnect(discardPending = true) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun tokenConnectSendsOriginAndBearerAndReadsState() = runBlocking {
        val expected = LifeState(revision = 7)
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), expected)))

        repository.connectToken(server.url("/").toString().trimEnd('/'), "life_test_token")

        val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("missing state request")
        assertEquals("GET", request.method)
        assertEquals("/api/state", request.path)
        assertEquals(server.url("/").toString().trimEnd('/'), request.getHeader("Origin"))
        assertEquals("Bearer life_test_token", request.getHeader("Authorization"))
        assertEquals(7L, repository.snapshot().state?.revision)
    }

    @Test
    fun uncertainPostRetriesTheSameImmutableMutation() = runBlocking {
        val origin = server.url("/").toString().trimEnd('/')
        val initial = LifeState(revision = 0)
        val accepted = initial.copy(revision = 1, cards = listOf(Card(id = "server-card", title = "Saved")))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        repository.connectToken(origin, "life_test_token")

        // The first refresh is followed by an uncertain 503 response.
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"temporary\"}"))
        repository.enqueue(Actions.capture("Offline capture"))
        repository.sync()
        assertEquals(1, repository.snapshot().pending)

        // A later run retries the same mutation id and timestamp and then acks it.
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), accepted)))
        repository.sync()
        assertEquals(0, repository.snapshot().pending)

        val requests = (0 until 5).map { server.takeRequest(2, TimeUnit.SECONDS) ?: error("missing request") }
        val posts = requests.filter { it.method == "POST" && it.path == "/api/actions" }
        assertEquals(2, posts.size)
        val firstPost = posts[0]
        val secondPost = posts[1]
        assertEquals("POST", firstPost.method)
        assertEquals("POST", secondPost.method)
        val mutationPattern = Regex("""\"mutation\":(\{.*\}),\"revision\"""")
        val firstMutation = mutationPattern.find(firstPost.body.readUtf8())!!.groupValues[1]
        val secondMutation = mutationPattern.find(secondPost.body.readUtf8())!!.groupValues[1]
        assertEquals(firstMutation, secondMutation)
    }

    @Test
    fun conflictRefreshesRevisionBeforeRetryingQueuedMutation() = runBlocking {
        val origin = server.url("/").toString().trimEnd('/')
        val initial = LifeState(revision = 0)
        val rebased = initial.copy(revision = 1)
        val accepted = rebased.copy(revision = 2, cards = listOf(Card(id = "saved", title = "Saved")))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        repository.connectToken(origin, "life_test_token")
        repository.enqueue(Actions.capture("Conflict capture"))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"revision\"}"))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), rebased)))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), accepted)))

        repository.sync()

        assertEquals(0, repository.snapshot().pending)
        assertEquals(2L, repository.snapshot().state?.revision)
        val requests = (0 until 5).map { server.takeRequest(2, TimeUnit.SECONDS) ?: error("missing request") }
        val actionPosts = requests.filter { it.path == "/api/actions" }
        assertEquals(2, actionPosts.size)
        val firstBody = actionPosts[0].body.readUtf8()
        val secondBody = actionPosts[1].body.readUtf8()
        assertTrue(firstBody.contains("Conflict capture"))
        val firstPayload = wireJson.parseToJsonElement(firstBody).jsonObject
        val secondPayload = wireJson.parseToJsonElement(secondBody).jsonObject
        assertEquals(firstPayload["mutation"], secondPayload["mutation"])
        assertEquals("0", firstPayload["revision"].toString())
        assertEquals("1", secondPayload["revision"].toString())
    }

    @Test
    fun failedDurableWriteDoesNotPublishMutationInMemory() = runBlocking {
        val failing = object : DurableStore {
            override fun read(): ByteArray? = null
            override fun write(plain: ByteArray) { error("disk full") }
        }
        val repositoryWithFailingDisk = LifeRepository.forTests(
            RuntimeEnvironment.getApplication(),
            OkHttpClient.Builder().build(),
            failing,
        )

        runCatching { repositoryWithFailingDisk.enqueue(Actions.capture("Must not publish")) }
        assertEquals(0, repositoryWithFailingDisk.snapshot().pending)
    }

    @Test
    fun appliedReceiptAfterLostResponseRemovesMutationWithoutSecondPost() = runBlocking {
        val origin = server.url("/").toString().trimEnd('/')
        val initial = LifeState(revision = 0)
        val accepted = initial.copy(revision = 1, cards = listOf(Card(id = "receipt-card", title = "Receipt")))
        var actionPosts = 0
        var queriedPending = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path == "/api/actions" -> {
                    actionPosts++
                    MockResponse().setResponseCode(503).setBody("temporary after commit")
                }
                request.path?.startsWith("/api/state") == true -> {
                    val ids = request.requestUrl?.queryParameter("mutations")
                    if (ids == null) {
                        jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial))
                    } else if (++queriedPending == 1) {
                        jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial))
                    } else {
                        jsonResponse(receiptBody(accepted, ids.split(',')))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        repository.connectToken(origin, "life_test_token")
        repository.enqueue(Actions.capture("Receipt capture"))
        repository.sync()
        assertEquals(1, repository.snapshot().pending)
        repository.sync()

        assertEquals(0, repository.snapshot().pending)
        assertEquals(1, actionPosts)
    }

    @Test
    fun sameOriginPasswordReauthKeepsAndDrainsPendingQueue() = runBlocking {
        val origin = server.url("/").toString().trimEnd('/')
        val initial = LifeState(revision = 0)
        val accepted = initial.copy(revision = 1, cards = listOf(Card(id = "reauth-card", title = "Reauth")))
        server.enqueue(loginResponse("life_session=one"))
        server.enqueue(jsonResponse("{\"token\":\"life_token_one\"}"))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        repository.connect(origin, "first-password")
        repository.enqueue(Actions.capture("Reauth capture"))

        server.enqueue(loginResponse("life_session=two"))
        server.enqueue(jsonResponse("{\"token\":\"life_token_two\"}"))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), accepted)))
        repository.connect(origin, "second-password")

        assertEquals(0, repository.snapshot().pending)
        val requests = (0 until 8).map { server.takeRequest(2, TimeUnit.SECONDS) ?: error("missing request") }
        assertTrue(requests[5].path!!.contains("mutations="))
        assertEquals("/api/actions", requests[7].path)
    }

    @Test
    fun htmlRedirectDuringSyncPreservesPendingAndMarksDisconnected() = runBlocking {
        val origin = server.url("/").toString().trimEnd('/')
        val initial = LifeState(revision = 0)
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), initial)))
        repository.connectToken(origin, "life_test_token")
        repository.enqueue(Actions.capture("Redirect capture"))
        server.enqueue(
            MockResponse().setResponseCode(302).addHeader("Location", "/login").setBody("<html>login</html>"),
        )

        repository.sync()

        val snapshot = repository.snapshot()
        assertEquals(1, snapshot.pending)
        assertTrue(!snapshot.connected)
        assertTrue(snapshot.error.orEmpty().contains("перенаправил", ignoreCase = true))
    }

    @Test
    fun passwordConnectExchangesCookieForTokenWithoutFollowingRedirects() = runBlocking {
        val origin = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setResponseCode(303)
                .addHeader("Location", "/")
                .addHeader("Set-Cookie", "life_session=memory-only; Path=/; HttpOnly"),
        )
        server.enqueue(jsonResponse("{\"token\":\"life_issued_token\"}"))
        server.enqueue(jsonResponse(wireJson.encodeToString(LifeState.serializer(), LifeState(revision = 2))))

        repository.connect(origin, "password-do-not-persist")

        val login = server.takeRequest(2, TimeUnit.SECONDS) ?: error("missing login request")
        val issue = server.takeRequest(2, TimeUnit.SECONDS) ?: error("missing token request")
        val state = server.takeRequest(2, TimeUnit.SECONDS) ?: error("missing state request")
        assertEquals("POST", login.method)
        assertEquals("/api/session/login", login.path)
        assertEquals(origin, login.getHeader("Origin"))
        assertEquals("life_session=memory-only", issue.getHeader("Cookie"))
        assertEquals(origin, issue.getHeader("Origin"))
        assertEquals("Bearer life_issued_token", state.getHeader("Authorization"))
        assertTrue(repository.snapshot().connected)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun loginResponse(cookie: String): MockResponse = MockResponse()
        .setResponseCode(303)
        .addHeader("Location", "/")
        .addHeader("Set-Cookie", "$cookie; Path=/; HttpOnly")

    private fun receiptBody(state: LifeState, ids: List<String>): String {
        val stateJson = wireJson.encodeToString(LifeState.serializer(), state)
        val applied = ids.joinToString(",") { "\"$it\"" }
        return "{\"state\":$stateJson,\"applied\":[$applied]}"
    }

    private class MemoryStore : DurableStore {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.clone()
        override fun write(plain: ByteArray) {
            value = plain.clone()
        }
    }
}
