package com.personallifeos.mobile.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personallifeos.mobile.model.Actions
import com.personallifeos.mobile.model.Board
import com.personallifeos.mobile.model.BoardColumn
import com.personallifeos.mobile.model.LifeState
import com.personallifeos.mobile.model.Mutation
import com.personallifeos.mobile.model.StateProjection
import com.personallifeos.mobile.model.Settings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side repository smoke against a process-local fixture.
 *
 * This intentionally exercises the public repository contract instead of reaching into its
 * mutexes or network client. The fixture is bound to localhost by MockWebServer, so the test
 * never needs a production URL, token, or adb reverse rule.
 */
@RunWith(AndroidJUnit4::class)
class LifeRepositoryInstrumentationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private lateinit var repository: LifeRepository
    private lateinit var postGate: CountDownLatch
    private val postedMutations = ConcurrentLinkedQueue<String>()
    private val queriedMutationIds = ConcurrentLinkedQueue<String>()
    private val appliedMutationIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var blockPosts = false
    private var postCount = 0

    private val fixtureState = LifeState(
        revision = 7,
        settings = Settings(autoArchiveCompleted = true),
        boards = listOf(Board("main", "Основная", columns = listOf(BoardColumn("inbox", "Входящие")))),
    )
    @Volatile private var currentState = fixtureState

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = fixtureDispatcher()
        server.start()
        repository = LifeRepository.get(context)
        postGate = CountDownLatch(1)
        blockPosts = false
        postedMutations.clear()
        queriedMutationIds.clear()
        appliedMutationIds.clear()
        currentState = fixtureState
        runBlocking {
            // Instrumentation runs against an isolated debug install. Explicitly clear a prior
            // fixture account so this test remains repeatable when run without reinstalling.
            runCatching { repository.disconnect(discardPending = true) }
            repository.connectToken(server.url("/").toString(), "fixture-token")
        }
        postCount = 0
    }

    @After
    fun tearDown() {
        runCatching {
            postGate.countDown()
            runBlocking { repository.disconnect(discardPending = true) }
        }
        server.shutdown()
    }

    @Test
    fun enqueueProjectsImmediatelyAndPersistsEncryptedOutboxBeforeNetworkSync() = runBlocking {
        val action = Actions.capture("Offline fixture task")
        blockPosts = true

        val started = System.nanoTime()
        repository.enqueue(action)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        val immediate = repository.snapshot()
        assertTrue("capture must be visible before a network round trip", immediate.connected)
        assertEquals(1, immediate.pending)
        assertEquals("Offline fixture task", immediate.state?.cards?.firstOrNull()?.title)
        assertTrue("enqueue must not wait for POST /api/actions (${elapsedMillis} ms)", elapsedMillis < 2_000)
        postGate.countDown()

        // EncryptedStateStore is intentionally replace-once and lives in no-backup storage. The
        // test only checks durability and absence of plaintext; it does not depend on ciphertext
        // layout or key material.
        val storeFile = context.noBackupFilesDir.resolve("life-repository.bin")
        assertTrue("outbox should be persisted before sync", storeFile.isFile)
        val bytes = storeFile.readBytes()
        assertTrue(bytes.isNotEmpty())
        val plaintext = bytes.toString(StandardCharsets.UTF_8)
        assertFalse("token must never be written as plaintext", plaintext.contains("fixture-token"))
        assertFalse("task title must be encrypted at rest", plaintext.contains("Offline fixture task"))
    }

    @Test
    fun syncPostsSameQueuedMutationAndClearsPendingOnFixtureAck() = runBlocking {
        repository.enqueue(Actions.capture("Sync fixture task"))
        assertEquals(1, repository.snapshot().pending)

        repository.sync()

        val synced = repository.snapshot()
        assertEquals(0, synced.pending)
        assertTrue(postCount >= 1)
        assertTrue(postedMutations.isNotEmpty())
        assertTrue("state refresh must carry the queued mutation id", queriedMutationIds.isNotEmpty())
        assertEquals("Sync fixture task", synced.state?.cards?.firstOrNull()?.title)
    }

    @Test
    fun manualSyncWithEmptyOutboxRefreshesStateWithoutPosting() = runBlocking {
        currentState = currentState.copy(
            cards = listOf(com.personallifeos.mobile.model.Card(
                id = "server-only",
                title = "Server refresh fixture",
            )),
            revision = currentState.revision + 1,
        )

        repository.sync()

        assertEquals(0, postCount)
        assertEquals("Server refresh fixture", repository.snapshot().state?.cards?.single()?.title)
    }

    @Test
    fun disconnectRefusesPendingOutboxUntilExplicitDiscard() = runBlocking {
        blockPosts = true
        repository.enqueue(Actions.capture("Disconnect safety fixture"))
        // Let a possible WorkManager run finish with a retryable fixture error before checking
        // disconnect. The mutation must stay durable while the server is unavailable.
        postGate.countDown()
        repository.sync()
        assertEquals(1, repository.snapshot().pending)

        try {
            repository.disconnect()
            fail("disconnect must refuse to drop a pending mutation")
        } catch (_: PendingMutationsException) {
            // Expected safety fence.
        }
        assertEquals(1, repository.snapshot().pending)
        repository.disconnect(discardPending = true)
        assertEquals(0, repository.snapshot().pending)
    }

    private fun fixtureDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when {
                request.method == "GET" && request.path?.startsWith("/api/state") == true -> {
                    require(request.getHeader("Authorization") == "Bearer fixture-token")
                    val path = requireNotNull(request.path)
                    val hasMutationQuery = path.contains("?mutations=")
                    if (hasMutationQuery) {
                        queriedMutationIds += path.substringAfter("?mutations=").split(',').filter { it.isNotBlank() }
                        jsonResponse("{\"state\":${json.encodeToString(currentState)},\"applied\":[]}")
                    } else {
                        jsonResponse(json.encodeToString(currentState))
                    }
                }

                request.method == "POST" && request.path == "/api/actions" -> {
                    require(request.getHeader("Authorization") == "Bearer fixture-token")
                    postCount += 1
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
                    // A retry after an uncertain/5xx response must reuse the same receipt id.
                    postedMutations += mutation.id
                    if (blockPosts) {
                        postGate.await(5, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(503)
                    }
                    if (appliedMutationIds.add(mutation.id)) {
                        currentState = StateProjection.apply(currentState, mutation)
                            .copy(revision = currentState.revision + 1)
                    }
                    jsonResponse(json.encodeToString(currentState))
                }

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
