package com.personallifeos.mobile.data

import android.content.Context
import com.personallifeos.mobile.BuildConfig
import com.personallifeos.mobile.model.LifeState
import com.personallifeos.mobile.model.Mutation
import com.personallifeos.mobile.model.StateProjection
import com.personallifeos.mobile.widgets.WidgetUpdates
import com.personallifeos.mobile.work.SyncScheduler
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType

/**
 * Offline-first repository for the personal space.
 *
 * The durable record contains only encrypted data.  The server state is kept
 * separately from the mutation queue: a server refresh can therefore rebase
 * the queue without losing a mutation that was enqueued while the request was
 * in flight.
 */
class LifeRepository private constructor(
    context: Context,
    private val http: OkHttpClient = defaultClient,
    private val durableStore: DurableStore = EncryptedStateStore(context.applicationContext),
    private val hooks: RepositoryHooks = RepositoryHooks.DEFAULT,
) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val stateLock = Mutex()
    private val networkLock = Mutex()
    private val loadLock = Mutex()
    private val loadedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val _snapshots = MutableStateFlow(MobileSnapshot())
    val snapshots: StateFlow<MobileSnapshot> = _snapshots

    private var loaded = false
    private var durable = DurableState()

    /** Returns the configured origin, or the documented production default. */
    fun baseUrl(): String = durable.server ?: DEFAULT_BASE_URL

    suspend fun snapshot(): MobileSnapshot {
        ensureLoaded()
        return stateLock.withLock { _snapshots.value }
    }

    /**
     * Logs in through the password form, exchanges the in-memory cookie for a
     * write token, then replaces the local cache with the fetched state.
     */
    suspend fun connect(baseUrl: String, password: String) {
        val server = normalizeBaseUrl(baseUrl)
        require(password.isNotEmpty()) { "Пароль не может быть пустым" }
        ensureLoaded()
        withContext(Dispatchers.IO) {
            networkLock.withLock {
                ensureCanSwitch(server, null)
                val cookie = passwordLogin(server, password)
                val token = issueToken(server, cookie)
                val pending = stateLock.withLock { durable.outbox }
                val response = fetchState(server, token, pending)
                stateLock.withLock {
                    // A queue created while fully offline has no account marker yet;
                    // attach it to the first successful connection. A configured
                    // different origin is a real account/server switch.
                    val sameAccount = durable.server == null || durable.server == server
                    if (!sameAccount && durable.outbox.isNotEmpty()) throw PendingMutationsException()
                    val nextOutbox = if (sameAccount) {
                        val applied = response.applied.toSet()
                        durable.outbox.filterNot { it.id in applied }
                    } else emptyList()
                    commitLocked(
                        DurableState(
                            server = server,
                            token = token,
                            state = response.state,
                            outbox = nextOutbox,
                            lastSyncedAt = now(),
                        ),
                    )
                    publishLocked(connected = true, error = null, blocked = false)
                }
            }
        }
        requestBackgroundSync()
        updateWidgets()
        if (snapshot().pending > 0) sync()
    }

    /** Connects using a token previously issued by the service. */
    suspend fun connectToken(baseUrl: String, token: String) {
        val server = normalizeBaseUrl(baseUrl)
        require(token.isNotBlank()) { "Токен не может быть пустым" }
        ensureLoaded()
        withContext(Dispatchers.IO) {
            networkLock.withLock {
            val current = stateLock.withLock { durable }
            val switchingAccount = (current.server != null && current.server != server) ||
                (current.token != null && current.token != token)
            if (switchingAccount && current.outbox.isNotEmpty()) {
                throw PendingMutationsException()
            }
            val response = fetchState(server, token, if (!switchingAccount) current.outbox else emptyList())
            stateLock.withLock {
                val next = if (switchingAccount) {
                    if (durable.outbox.isNotEmpty()) throw PendingMutationsException()
                    DurableState(server = server, token = token, state = response.state)
                } else {
                    durable.copy(server = server, token = token, state = response.state)
                        .removeApplied(response.applied)
                }
                commitLocked(next.copy(lastSyncedAt = now()))
                publishLocked(connected = true, error = null, blocked = false)
            }
            }
        }
        requestBackgroundSync()
        updateWidgets()
        // A token reconnect should also drain an already durable queue.
        if (snapshot().pending > 0) sync()
    }

    /**
     * Adds an immutable mutation to disk and immediately projects it locally.
     * Only the state mutex is held during this operation; network work is
     * deliberately scheduled after it is released.
     */
    suspend fun enqueue(action: JsonObject) {
        ensureLoaded()
        val mutation = Mutation(
            id = UUID.randomUUID().toString(),
            at = now(),
            action = action,
        )
        withContext(Dispatchers.IO) {
            stateLock.withLock {
                if (durable.outbox.size >= MAX_PENDING) {
                    publishLocked(error = "Слишком много несохранённых действий", blocked = true)
                    throw QueueFullException()
                }
                commitLocked(durable.copy(outbox = durable.outbox + mutation))
                publishLocked(error = null, blocked = false)
            }
        }
        requestBackgroundSync()
        updateWidgets()
    }

    /** Drains the queue in order, retaining the exact mutation across retries. */
    suspend fun sync() {
        ensureLoaded()
        withContext(Dispatchers.IO) {
            networkLock.withLock {
            stateLock.withLock { publishLocked(syncing = true, error = null) }
            try {
                val credentials = stateLock.withLock { durable.server to durable.token }
                val server = credentials.first
                val token = credentials.second
                if (server == null || token == null) {
                    stateLock.withLock {
                        publishLocked(syncing = false, connected = false, error = "Подключите сервер")
                    }
                    return@withLock
                }
                var refreshes = 0
                refresh@ while (true) {
                    val pending = stateLock.withLock { durable.outbox }
                    val refreshed = fetchState(server, token, pending)
                    applyRefresh(refreshed)
                    while (true) {
                        val mutation = stateLock.withLock { durable.outbox.firstOrNull() } ?: break
                        val revision = stateLock.withLock { durable.state?.revision ?: refreshed.state.revision }
                        try {
                            val result = postMutation(server, token, mutation, revision)
                            acknowledge(mutation.id, result)
                        } catch (e: HttpRepositoryException) {
                            when (e.status) {
                                409 -> {
                                    if (++refreshes > MAX_CONFLICT_REFRESHES) {
                                        markError("Данные менялись слишком часто. Повторите синхронизацию.", false)
                                        break@refresh
                                    }
                                    continue@refresh
                                }
                                400, 422 -> {
                                    markError(e.message ?: "Действие отклонено сервером", true)
                                    break@refresh
                                }
                                401, 403 -> {
                                    markError(e.message ?: "Токен требует обновления", true, connected = false)
                                    break@refresh
                                }
                                else -> {
                                    markError(e.message ?: "Сервис временно недоступен", false)
                                    break@refresh
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            markError("Нет соединения с сервером", false)
                            break@refresh
                        } catch (e: Exception) {
                            markError(e.message ?: "Не удалось синхронизировать данные", false)
                            break@refresh
                        }
                    }
                    break
                }
                stateLock.withLock {
                    publishLocked(
                        syncing = false,
                        connected = durable.token != null && _snapshots.value.connected,
                    )
                }
            } catch (e: CancellationException) {
                stateLock.withLock { publishLocked(syncing = false) }
                throw e
            } catch (e: HttpRepositoryException) {
                markError(e.message ?: "Сервис временно недоступен", false, connected = e.status !in 401..403)
                stateLock.withLock { publishLocked(syncing = false) }
            } catch (e: IOException) {
                markError("Нет соединения с сервером", false)
                stateLock.withLock { publishLocked(syncing = false) }
            } catch (e: Exception) {
                markError(e.message ?: "Не удалось синхронизировать данные", false)
                stateLock.withLock { publishLocked(syncing = false) }
                }
            }
        }
        updateWidgets()
    }

    /** Disconnects, refusing to make a durable queue disappear by accident. */
    suspend fun disconnect(discardPending: Boolean = false) {
        ensureLoaded()
        withContext(Dispatchers.IO) {
            networkLock.withLock {
                stateLock.withLock {
                    if (durable.outbox.isNotEmpty() && !discardPending) throw PendingMutationsException()
                    commitLocked(DurableState())
                    publishLocked(connected = false, error = null, blocked = false)
                }
            }
        }
        updateWidgets()
    }

    private suspend fun ensureLoaded() {
        if (loaded) {
            loadedSignal.await()
            return
        }
        loadLock.withLock {
            if (!loaded) {
                val loadedValue = try {
                    withContext(Dispatchers.IO) {
                        durableStore.read()?.let { json.decodeFromString(DurableState.serializer(), it.toString(Charsets.UTF_8)) }
                            ?: DurableState()
                    }
                } catch (e: Exception) {
                    stateLock.withLock {
                        publishLocked(error = "Не удалось прочитать локальные данные")
                    }
                    throw IllegalStateException("Не удалось прочитать локальные данные", e)
                }
                stateLock.withLock {
                    durable = loadedValue
                    publishLocked(connected = loadedValue.token != null, error = null)
                }
                loaded = true
                loadedSignal.complete(Unit)
            }
        }
        loadedSignal.await()
    }

    private suspend fun ensureCanSwitch(server: String, token: String?) {
        stateLock.withLock {
            val switching = (durable.server != null && durable.server != server) ||
                (token != null && durable.token != token)
            if (switching && durable.outbox.isNotEmpty()) throw PendingMutationsException()
        }
    }

    private suspend fun applyRefresh(response: StateResponse) {
        stateLock.withLock {
            commitLocked(
                durable.copy(state = response.state, lastSyncedAt = now()).removeApplied(response.applied),
            )
            publishLocked(connected = true, error = null, blocked = false)
        }
    }

    private suspend fun acknowledge(id: String, response: ActionResponse) {
        stateLock.withLock {
            val current = durable
            commitLocked(current.copy(
                state = response.state,
                outbox = current.outbox.filterNot { it.id == id },
                lastSyncedAt = now(),
            ))
            publishLocked(connected = true, error = null, blocked = false)
        }
    }

    private suspend fun markError(message: String, blocked: Boolean, connected: Boolean? = null) {
        stateLock.withLock {
            publishLocked(error = message.take(1000), blocked = blocked, connected = connected)
        }
    }

    private fun DurableState.removeApplied(applied: List<String>): DurableState {
        if (applied.isEmpty()) return this
        val ids = applied.toSet()
        return copy(outbox = outbox.filterNot { it.id in ids })
    }

    /** Writes first; only a successful write changes the in-memory durable record. */
    private fun commitLocked(next: DurableState) {
        val value = json.encodeToString(DurableState.serializer(), next)
        durableStore.write(value.toByteArray(Charsets.UTF_8))
        durable = next
    }

    /** Must only be called while [stateLock] is held. */
    private fun publishLocked(
        syncing: Boolean = _snapshots.value.syncing,
        error: String? = _snapshots.value.error,
        connected: Boolean? = null,
        blocked: Boolean = _snapshots.value.blocked,
    ) {
        val projected = durable.state?.let { base ->
            durable.outbox.fold(base) { state, mutation ->
                runCatching { StateProjection.apply(state, mutation) }.getOrDefault(state)
            }
        }
        _snapshots.value = MobileSnapshot(
            state = projected,
            pending = durable.outbox.size,
            syncing = syncing,
            error = error,
            connected = connected ?: _snapshots.value.connected,
            lastSyncedAt = durable.lastSyncedAt,
            blocked = blocked,
        )
    }

    private fun passwordLogin(server: String, password: String): String {
        val body = FormBody.Builder().add("return_to", "/").add("password", password).build()
        val request = Request.Builder()
            .url("$server/api/session/login")
            .header("Origin", server)
            .header("Accept", "text/html,application/xhtml+xml")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code !in 300..399) throw HttpRepositoryException(response.code, "Не удалось войти. Проверьте пароль.")
            return response.header("Set-Cookie")?.substringBefore(';')
                ?: throw HttpRepositoryException(response.code, "Сервис не выдал сессию")
        }
    }

    private fun issueToken(server: String, cookie: String): String {
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("name", JsonPrimitive("Android"))
                put("scope", JsonPrimitive("write"))
            },
        ).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$server/api/tokens")
            .header("Origin", server)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw HttpRepositoryException(
                401, "Сервер перенаправил запрос. Проверьте адрес и войдите снова.",
            )
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpRepositoryException(response.code, serverError(raw, "Не удалось получить токен"))
            val objectValue = raw.parseObject()
            return objectValue["token"]?.jsonPrimitive?.content
                ?: throw HttpRepositoryException(response.code, "Сервис не выдал токен")
        }
    }

    private fun fetchState(server: String, token: String, pending: List<Mutation>): StateResponse {
        val url = server.toHttpUrl().newBuilder().addPathSegments("api/state")
        if (pending.isNotEmpty()) url.addQueryParameter("mutations", pending.joinToString(",") { it.id })
        val request = authorized(url.build().toString(), server, token).get().build()
        http.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw HttpRepositoryException(
                401, "Сервер перенаправил запрос. Проверьте адрес и войдите снова.",
            )
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpRepositoryException(response.code, serverError(raw, "Не удалось загрузить данные"))
            val objectValue = raw.parseObject()
            val stateElement = objectValue["state"] ?: objectValue
            val state = json.decodeFromJsonElement(LifeState.serializer(), stateElement)
            val applied = objectValue["applied"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            return StateResponse(state, applied)
        }
    }

    private fun postMutation(server: String, token: String, mutation: Mutation, revision: Long): ActionResponse {
        val mutationElement = json.encodeToJsonElement(Mutation.serializer(), mutation)
        val body = buildJsonObject {
            put("mutation", mutationElement)
            put("revision", JsonPrimitive(revision))
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = authorized("$server/api/actions", server, token)
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw HttpRepositoryException(
                401, "Сервер перенаправил запрос. Проверьте адрес и войдите снова.",
            )
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpRepositoryException(response.code, serverError(raw, "Не удалось сохранить действие"))
            val objectValue = raw.parseObject()
            val state = objectValue["state"]?.let {
                runCatching { json.decodeFromJsonElement(LifeState.serializer(), it) }.getOrNull()
            } ?: if (objectValue.containsKey("cards")) {
                runCatching { json.decodeFromJsonElement(LifeState.serializer(), objectValue) }.getOrNull()
            } else null
            return ActionResponse(state ?: throw HttpRepositoryException(502, "Сервис не вернул сохранённое состояние"))
        }
    }

    private fun authorized(url: String, server: String, token: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Origin", server)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    private fun String.parseObject(): JsonObject = runCatching { json.parseToJsonElement(this).jsonObject }
        .getOrElse { throw HttpRepositoryException(502, "Сервис вернул некорректный ответ") }

    private fun serverError(raw: String, fallback: String): String =
        runCatching { raw.parseObject()["error"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    private suspend fun requestBackgroundSync() {
        runCatching { hooks.enqueue(appContext) }
    }

    private suspend fun updateWidgets() {
        runCatching { hooks.widgets(appContext) }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://personal-life-os.51-250-78-132.sslip.io"
        private const val MAX_PENDING = 100
        private const val MAX_CONFLICT_REFRESHES = 8
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        @Volatile private var instance: LifeRepository? = null

        fun get(context: Context): LifeRepository = instance ?: synchronized(this) {
            instance ?: LifeRepository(context).also { instance = it }
        }

        internal fun forTests(
            context: Context,
            client: OkHttpClient,
            store: DurableStore,
            hooks: RepositoryHooks = RepositoryHooks.NO_OP,
        ): LifeRepository = LifeRepository(context, client, store, hooks)

        private fun normalizeBaseUrl(value: String): String {
            val origin = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("Некорректный адрес сервера") }
            val host = origin.host?.lowercase() ?: throw IllegalArgumentException("Некорректный адрес сервера")
            val secure = origin.scheme.equals("https", ignoreCase = true)
            val local = host == "localhost" || host == "127.0.0.1" || host == "::1"
            require(origin.path.isNullOrEmpty() || origin.path == "/") {
                "В адресе сервера недопустим путь"
            }
            require(origin.port == -1 || origin.port in 1..65535) {
                "Некорректный порт сервера"
            }
            require(secure || (BuildConfig.DEBUG && origin.scheme.equals("http", ignoreCase = true) && local)) {
                "Сервер должен использовать HTTPS"
            }
            require(origin.userInfo == null && origin.query == null && origin.fragment == null) {
                "В адресе сервера недопустимы параметры"
            }
            return value.trim().toHttpUrl().newBuilder().encodedPath("/").build().toString().trimEnd('/')
        }

        private fun now(): String = DateTimeFormatterBuilder()
            .appendInstant(3)
            .toFormatter()
            .format(Instant.now().truncatedTo(ChronoUnit.MILLIS))
    }
}

internal data class RepositoryHooks(
    val enqueue: (Context) -> Unit,
    val widgets: suspend (Context) -> Unit,
) {
    companion object {
        val DEFAULT = RepositoryHooks(
            enqueue = { SyncScheduler.enqueue(it) },
            widgets = { WidgetUpdates.update(it) },
        )
        val NO_OP = RepositoryHooks(enqueue = {}, widgets = {})
    }
}

@Serializable
private data class DurableState(
    val server: String? = null,
    val token: String? = null,
    val state: LifeState? = null,
    val outbox: List<Mutation> = emptyList(),
    val lastSyncedAt: String? = null,
)

private data class StateResponse(val state: LifeState, val applied: List<String>)
private data class ActionResponse(val state: LifeState)
private class HttpRepositoryException(val status: Int, override val message: String) : IOException(message)
class PendingMutationsException : IllegalStateException("Сначала синхронизируйте несохранённые действия")
class QueueFullException : IllegalStateException("Очередь действий заполнена")
