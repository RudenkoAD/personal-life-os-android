package com.personallifeos.mobile.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.data.MobileSnapshot
import com.personallifeos.mobile.model.Actions
import com.personallifeos.mobile.model.CalendarItem
import com.personallifeos.mobile.model.CalendarProjection
import com.personallifeos.mobile.model.Card
import com.personallifeos.mobile.model.CardType
import com.personallifeos.mobile.model.LifeState
import com.personallifeos.mobile.model.Placement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch

private val Accent = Color(0xFF4F5FD2)
private val LightColors = lightColorScheme(primary = Accent, secondary = Color(0xFF52628F))
private val DarkColors = darkColorScheme(primary = Color(0xFFB9C1FF), secondary = Color(0xFFBBC5F6))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeOsApp(repository: LifeRepository, deepLink: DeepLink, onDeepLink: (DeepLink) -> Unit) {
    val context = LocalContext.current
    val themePreferences = remember { context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(themePreferences.getString("theme_mode", "system") ?: "system") }
    val dark = themeMode == "dark" || (themeMode == "system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        Surface(Modifier.fillMaxSize()) {
            LifeOsContent(repository, deepLink, onDeepLink, themeMode) {
                themeMode = it
                themePreferences.edit().putString("theme_mode", it).apply()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LifeOsContent(
    repository: LifeRepository,
    deepLink: DeepLink,
    onDeepLink: (DeepLink) -> Unit,
    themeMode: String,
    setThemeMode: (String) -> Unit,
) {
    val snapshot by repository.snapshots.collectAsState()
    var repositoryLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(repository) {
        try { repository.snapshot() } catch (_: Exception) { }
        repositoryLoaded = true
    }
    if (!repositoryLoaded) {
        LoadingState()
        return
    }
    val state = snapshot.state
    if (!snapshot.connected) {
        LoginScreen(repository, snapshot)
        return
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(if (deepLink.screen in setOf("calendar", "settings")) deepLink.screen else "inbox") }
    var selectedDate by rememberSaveable { mutableStateOf(deepLink.date ?: LocalDate.now(CalendarProjection.zone).toString()) }
    var selectedId by rememberSaveable { mutableStateOf(deepLink.id) }
    var sheet by rememberSaveable { mutableStateOf(if (deepLink.screen == "capture") "capture" else "") }
    var captureBoardId by rememberSaveable { mutableStateOf(deepLink.captureBoardId) }
    var captureColumnId by rememberSaveable { mutableStateOf(deepLink.captureColumnId) }
    var inboxBoardId by rememberSaveable { mutableStateOf<String?>(null) }
    var inboxColumnId by rememberSaveable { mutableStateOf<String?>(null) }
    var handledDeepLinkRequest by rememberSaveable { mutableLongStateOf(0L) }
    var taskEditor by remember { mutableStateOf<Card?>(null) }
    var eventEditor by remember { mutableStateOf<CalendarItem?>(null) }
    var createEvent by rememberSaveable { mutableStateOf(deepLink.screen == "event_create") }
    val localDate = parseDate(selectedDate)

    LaunchedEffect(deepLink) {
        if (deepLink.screen in setOf("inbox", "calendar", "settings")) screen = deepLink.screen
        if (deepLink.screen == "inbox") {
            inboxBoardId = deepLink.captureBoardId
            inboxColumnId = deepLink.captureColumnId
        }
        if (deepLink.screen == "event_create") {
            screen = "calendar"
            createEvent = true
        }
        deepLink.date?.let { selectedDate = it }
        deepLink.id?.let { selectedId = it }
        if (deepLink.screen == "capture") {
            captureBoardId = deepLink.captureBoardId
            captureColumnId = deepLink.captureColumnId
            sheet = "capture"
        }
        // Keep the original intent state so task/event deep links are not lost while
        // the repository finishes its initial disk load.
    }
    LaunchedEffect(deepLink, state) {
        if (deepLink.id == null || state == null || handledDeepLinkRequest == deepLink.requestId) return@LaunchedEffect
        var opened = false
        when (deepLink.screen) {
            "task" -> state.cards.firstOrNull { it.id == deepLink.id }?.let { taskEditor = it; opened = true }
            "event" -> {
                val day = parseDate(deepLink.date ?: selectedDate)
                CalendarProjection.items(state, day.minusDays(1), day.plusDays(2)).firstOrNull { it.id == deepLink.id }?.let { eventEditor = it; opened = true }
            }
        }
        if (opened) handledDeepLinkRequest = deepLink.requestId
    }
    val enqueue: (suspend () -> Unit) -> Unit = { operation ->
        scope.launch {
            try { operation(); snackbar.showSnackbar("Добавлено в очередь") }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "Не удалось сохранить") }
        }
    }
    val submit: suspend (suspend () -> Unit) -> Boolean = { operation ->
        try {
            operation()
            true
        } catch (error: Exception) {
            snackbar.showSnackbar(error.message ?: "Не удалось сохранить")
            false
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(if (taskEditor != null) "Задача" else if (eventEditor != null) "Событие" else when (screen) { "calendar" -> "Календарь"; "settings" -> "Настройки"; else -> "Входящие" }) },
                navigationIcon = {
                    if (taskEditor != null || eventEditor != null) IconButton(onClick = { taskEditor = null; eventEditor = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    if (taskEditor == null && eventEditor == null) {
                        IconButton(onClick = { scope.launch { repository.sync() } }, modifier = Modifier.semantics { contentDescription = "Синхронизировать" }) { Icon(Icons.Default.Sync, null) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (taskEditor == null && eventEditor == null) BottomAppBar {
                NavigationBarItem(screen == "inbox", { screen = "inbox"; inboxBoardId = null; inboxColumnId = null }, icon = { Icon(Icons.AutoMirrored.Filled.List, "Входящие") }, label = { Text("Входящие") }, modifier = Modifier.testTag("nav_inbox"))
                NavigationBarItem(screen == "calendar", { screen = "calendar" }, icon = { Icon(Icons.Default.CalendarMonth, "Календарь") }, label = { Text("Календарь") }, modifier = Modifier.testTag("nav_calendar"))
                NavigationBarItem(screen == "settings", { screen = "settings" }, icon = { Icon(Icons.Default.Settings, "Настройки") }, label = { Text("Настройки") }, modifier = Modifier.testTag("nav_settings"))
            }
        },
        floatingActionButton = {
            if (taskEditor == null && eventEditor == null && (screen == "inbox" || screen == "calendar")) FloatingActionButton(onClick = { if (screen == "calendar") createEvent = true else { captureBoardId = null; captureColumnId = null; sheet = "capture" } }, modifier = Modifier.testTag("inbox_add")) { Icon(Icons.Default.Add, "Добавить") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                taskEditor != null -> TaskEditor(taskEditor!!, state ?: LifeState(), repository, submit, { taskEditor = null })
                eventEditor != null -> EventDetail(eventEditor!!)
                state == null -> LoadingState()
                screen == "calendar" -> CalendarScreen(state, localDate, { selectedDate = it.toString() }, { item ->
                    selectedId = item.id
                    if (item.cardId != null) taskEditor = state.cards.firstOrNull { card -> card.id == item.cardId }
                    else eventEditor = item
                }, { createEvent = true })
                screen == "settings" -> SettingsScreen(repository, snapshot, themeMode, setThemeMode)
                else -> InboxScreen(state, snapshot, inboxBoardId, inboxColumnId, { inboxBoardId = null; inboxColumnId = null }, { card -> selectedId = card.id; taskEditor = card }, enqueue, repository)
            }
        }
    }
    if (sheet == "capture") CaptureSheet({ sheet = "" }) { title ->
        // Keep the sheet and its draft open until the durable enqueue succeeds. A network
        // failure must leave the user's text available for retry.
        scope.launch {
            try {
                val boardId = captureBoardId
                val selectedBoard = boardId?.let { id -> state?.boards?.firstOrNull { board -> board.id == id } }
                val columnId = captureColumnId
                    ?: selectedBoard?.columns?.firstOrNull()?.id
                    ?: ""
                val action = if (boardId != null) Actions.create(title, boardId, columnId) else Actions.capture(title)
                repository.enqueue(action)
                snackbar.showSnackbar("Добавлено в очередь")
                captureBoardId = null
                captureColumnId = null
                sheet = ""
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "Не удалось сохранить")
            }
        }
    }
    if (createEvent) EventCreateDialog(parseDate(selectedDate), { createEvent = false }) { title, date, time, duration, allDay ->
        scope.launch {
            if (submit { repository.enqueue(Actions.eventCreate(title, date, time, duration, allDay)) }) createEvent = false
        }
    }
}

@Composable
private fun LoadingState() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }

@Composable
private fun LoginScreen(repository: LifeRepository, snapshot: MobileSnapshot) {
    val scope = rememberCoroutineScope()
    var origin by rememberSaveable { mutableStateOf(repository.baseUrl().ifBlank { "https://personal-life-os.51-250-78-132.sslip.io" }) }
    var password by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Life OS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        snapshot.error?.let {
            Text(
                if (snapshot.pending > 0) "Не удалось отправить ${snapshot.pending} изменений. Проверьте подключение и войдите снова.\n$it" else it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (snapshot.blocked) Text("Очередь заблокирована: исправьте данные и повторите отправку.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(origin, { origin = it }, Modifier.fillMaxWidth().testTag("login_origin"), singleLine = true, label = { Text("Адрес сервиса") })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("login_password"), singleLine = true, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && password.isNotBlank(), onClick = { busy = true; error = null; scope.launch { runCatching { repository.connect(origin.trim(), password) }.onFailure { error = it.message ?: "Ошибка подключения" }; busy = false } }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("login_connect")) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Войти")
            }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть расширенные" else "Токен") }
        if (advanced) {
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().testTag("login_token"), singleLine = true, label = { Text("Токен") }, visualTransformation = PasswordVisualTransformation())
            OutlinedButton(enabled = !busy && token.isNotBlank(), onClick = { busy = true; error = null; scope.launch { runCatching { repository.connectToken(origin.trim(), token) }.onFailure { error = it.message ?: "Ошибка подключения" }; busy = false } }, modifier = Modifier.fillMaxWidth()) { Text("Войти по токену") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun InboxScreen(
    state: LifeState,
    snapshot: MobileSnapshot,
    boardId: String?,
    columnId: String?,
    clearFilter: () -> Unit,
    open: (Card) -> Unit,
    enqueue: ((suspend () -> Unit) -> Unit),
    repository: LifeRepository,
) {
    val board = boardId?.let { id -> state.boards.firstOrNull { it.id == id } }
    val cards = state.cards.filter { card ->
        !card.archived && if (boardId == null) card.placement == Placement.inbox
        else card.boardId == boardId && (columnId == null || card.columnId == columnId)
    }
    Column(Modifier.fillMaxSize()) {
        if (boardId != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (columnId == null) board?.title ?: "Доска" else "${board?.title ?: "Доска"} · ${board?.columns?.firstOrNull { it.id == columnId }?.title ?: "Колонка"}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = clearFilter) { Text("Все") }
        }
        if (snapshot.blocked) Text("Синхронизация заблокирована", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
        else if (snapshot.error != null) Text("Локально сохранено · ${snapshot.error}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
        else if (snapshot.pending > 0) Text("В очереди: ${snapshot.pending}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.settings.autoArchiveCompleted) Text("Завершённые скрываются автоматически", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (cards.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Входящие пусты", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            items(cards, key = { it.id }) { card ->
                TaskRow(card, open) { done -> enqueue { repository.enqueue(Actions.complete(card.id, done)) } }
            }
        }
    }
}

@Composable
private fun TaskRow(card: Card, open: (Card) -> Unit, onDone: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { open(card) }.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(card.done, onDone, Modifier.testTag("task_complete_${card.id}").semantics { contentDescription = "Завершить ${card.title}" })
        Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
            Text(card.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (card.done) FontWeight.Normal else FontWeight.Medium)
            if (card.notes.isNotBlank()) Text(card.notes.replace('\n', ' '), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (card.done) Icon(Icons.Default.Check, "Готово", tint = MaterialTheme.colorScheme.primary)
    }
    Divider(Modifier.padding(start = 58.dp))
}

@Composable
private fun TaskEditor(card: Card, state: LifeState, repository: LifeRepository, submit: suspend (suspend () -> Unit) -> Boolean, close: () -> Unit) {
    var title by remember(card.id) { mutableStateOf(card.title) }
    var notes by remember(card.id) { mutableStateOf(card.notes) }
    var done by remember(card.id) { mutableStateOf(card.done) }
    val initialStart = remember(card.id) { card.start?.let { runCatching { Instant.parse(it).atZone(CalendarProjection.zone) }.getOrNull() } }
    var startDate by remember(card.id) { mutableStateOf(initialStart?.toLocalDate()?.toString() ?: "") }
    var startTime by remember(card.id) { mutableStateOf(initialStart?.toLocalTime()?.withSecond(0)?.toString()?.take(5) ?: "09:00") }
    var durationMinutes by remember(card.id) { mutableIntStateOf(60) }
    var boardId by remember(card.id) { mutableStateOf(card.boardId) }
    var columnId by remember(card.id) { mutableStateOf(card.columnId) }
    var boardMenu by remember { mutableStateOf(false) }
    var columnMenu by remember { mutableStateOf(false) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val board = state.boards.firstOrNull { it.id == boardId }
    val columns = board?.columns.orEmpty()
    val parseSchedule: () -> Instant? = {
        val date = runCatching { LocalDate.parse(startDate) }.getOrNull()
        val time = runCatching { LocalTime.parse(startTime) }.getOrNull()
        if (date == null || time == null) {
            scheduleError = "Введите дату ГГГГ-ММ-ДД и время ЧЧ:ММ"
            null
        } else {
            scheduleError = null
            date.atTime(time).atZone(CalendarProjection.zone).toInstant()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().height(130.dp), label = { Text("Заметки") })
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(done, { checked -> done = checked }, Modifier.testTag("task_editor_complete")); Text("Готово") }
        if (card.type != CardType.project) {
            Text("Планирование", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startDate, { startDate = it; scheduleError = null }, Modifier.weight(1f), label = { Text("Дата") }, singleLine = true)
                OutlinedTextField(startTime, { startTime = it; scheduleError = null }, Modifier.weight(1f), label = { Text("Время") }, singleLine = true)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                    FilterChip(durationMinutes == minutes, { durationMinutes = minutes }, label = { Text("${minutes}м") })
                }
            }
            scheduleError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = startDate.isNotBlank() && startTime.isNotBlank(), onClick = {
                    val start = parseSchedule()
                    if (start != null) scope.launch { submit { repository.enqueue(Actions.schedule(card.id, start, start.plusSeconds(durationMinutes * 60L))) } }
                }, modifier = Modifier.weight(1f)) { Text("Запланировать") }
                OutlinedButton(onClick = { scope.launch { submit { repository.enqueue(Actions.inbox(card.id)) } } }, modifier = Modifier.weight(1f)) { Text("Во входящие") }
            }
        }
        Text("Перемещение", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { boardMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(board?.title ?: "Выберите доску") }
            DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
                state.boards.forEach { candidate ->
                    DropdownMenuItem(text = { Text(candidate.title) }, onClick = { boardId = candidate.id; columnId = candidate.columns.firstOrNull()?.id.orEmpty(); boardMenu = false })
                }
            }
        }
        if (columns.isNotEmpty()) Box {
            OutlinedButton(onClick = { columnMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(columns.firstOrNull { it.id == columnId }?.title ?: "Выберите колонку") }
            DropdownMenu(expanded = columnMenu, onDismissRequest = { columnMenu = false }) {
                columns.forEach { candidate ->
                    DropdownMenuItem(text = { Text(candidate.title) }, onClick = { columnId = candidate.id; columnMenu = false })
                }
            }
        }
        OutlinedButton(enabled = boardId.isNotBlank() && columnId.isNotBlank(), onClick = { scope.launch { submit { repository.enqueue(Actions.move(card.id, boardId, columnId)) } } }, modifier = Modifier.fillMaxWidth()) { Text("Переместить") }
        Button(enabled = title.isNotBlank(), onClick = {
            scope.launch {
                if (submit { repository.enqueue(Actions.update(card.id, title.trim(), notes)); if (done != card.done) repository.enqueue(Actions.complete(card.id, done)) }) close()
            }
        }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("task_save")) { Text("Сохранить") }
        OutlinedButton(onClick = close, Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}

@Composable
private fun CalendarScreen(state: LifeState, date: LocalDate, selectDate: (LocalDate) -> Unit, open: (CalendarItem) -> Unit, create: () -> Unit) {
    var month by rememberSaveable(date.year, date.monthValue) { mutableStateOf(date.withDayOfMonth(1)) }
    var mode by rememberSaveable { mutableStateOf("agenda") }
    val from = if (mode == "month") month.withDayOfMonth(1).minusDays(7) else date
    val to = if (mode == "month") month.withDayOfMonth(1).plusMonths(1).plusDays(7) else date.plusDays(1)
    val items = CalendarProjection.items(state, from, to)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(mode == "agenda", { mode = "agenda" }, label = { Text("День") })
            FilterChip(mode == "month", { mode = "month" }, label = { Text("Месяц") })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (mode == "month") month = month.minusMonths(1) else selectDate(date.minusDays(1)) }) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text(if (mode == "month") month.format(DateTimeFormatter.ofPattern("LLLL yyyy")) else date.format(DateTimeFormatter.ofPattern("d MMMM")), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { if (mode == "month") month = month.plusMonths(1) else selectDate(date.plusDays(1)) }) { Icon(Icons.Default.ArrowForward, "Вперёд") }
        }
        if (mode == "month") MonthGrid(month, items, selectDate, open)
        else Agenda(date, items, open)
    }
}

@Composable
private fun Agenda(date: LocalDate, items: List<CalendarItem>, open: (CalendarItem) -> Unit) {
    if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Нет событий", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    else LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) { items(items, key = { it.id + it.start.toString() }) { item -> CalendarRow(item, open) } }
}

@Composable
private fun CalendarRow(item: CalendarItem, open: (CalendarItem) -> Unit) {
    val local = item.start.atZone(CalendarProjection.zone)
    Row(Modifier.fillMaxWidth().clickable { open(item) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(if (item.allDay) "весь день" else local.format(DateTimeFormatter.ofPattern("HH:mm")), Modifier.width(65.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) { Text(item.title, fontWeight = FontWeight.Medium); if (item.location.isNotBlank()) Text(item.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Divider(Modifier.padding(start = 81.dp))
}

@Composable
private fun MonthGrid(month: LocalDate, items: List<CalendarItem>, select: (LocalDate) -> Unit, open: (CalendarItem) -> Unit) {
    val first = month.withDayOfMonth(1)
    val start = first.minusDays(((first.dayOfWeek.value + 6) % 7).toLong())
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, Modifier.weight(1f).padding(3.dp), style = MaterialTheme.typography.labelSmall) } }
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { day ->
                    val date = start.plusDays((week * 7L) + day)
                    val dayStart = date.atStartOfDay(CalendarProjection.zone).toInstant()
                    val dayEnd = date.plusDays(1).atStartOfDay(CalendarProjection.zone).toInstant()
                    val dayItems = items.filter { it.start.isBefore(dayEnd) && it.end.isAfter(dayStart) }
                    Surface(Modifier.weight(1f).height(62.dp).padding(2.dp).clickable { select(date) }, shape = RoundedCornerShape(6.dp), color = if (date.year == month.year && date.month == month.month) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                        Column(Modifier.padding(4.dp)) { Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall); dayItems.take(2).forEach { Text("• ${it.title}", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { open(it) }) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDetail(item: CalendarItem) {
    val start = item.start.atZone(CalendarProjection.zone)
    val end = item.end.atZone(CalendarProjection.zone)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AssistChip(onClick = {}, enabled = false, label = { Text(if (item.sourceId == "local") "Событие" else "Импортировано") })
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        Text(if (item.allDay) "Весь день · ${start.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}" else "${start.format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm"))}–${end.format(DateTimeFormatter.ofPattern("HH:mm"))}")
        if (item.notes.isNotBlank()) Text(item.notes)
        if (item.location.isNotBlank()) Text(item.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.sourceId != "local") Text("Импортированное событие доступно только для чтения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureSheet(close: () -> Unit, save: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Быстрая запись", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().testTag("capture_title"), label = { Text("Что нужно сделать?") }, singleLine = true)
            Button(enabled = title.isNotBlank(), onClick = { save(title.trim()) }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("capture_save")) { Text("Добавить") }
        }
    }
}

@Composable
private fun EventCreateDialog(initialDate: LocalDate, close: () -> Unit, save: (String, LocalDate, LocalTime, Int, Boolean) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable(initialDate.toString()) { mutableStateOf(initialDate.toString()) }
    var time by rememberSaveable { mutableStateOf("09:00") }
    var duration by rememberSaveable { mutableIntStateOf(60) }
    var allDay by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("Новое событие") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true)
        OutlinedTextField(date, { date = it; formError = null }, label = { Text("Дата, ГГГГ-ММ-ДД") }, singleLine = true)
        if (!allDay) OutlinedTextField(time, { time = it; formError = null }, label = { Text("Время") }, singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(15, 30, 45, 60, 90, 120).forEach { minutes -> FilterChip(duration == minutes, { duration = minutes }, label = { Text("${minutes}м") }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allDay, { allDay = it }); Text("Весь день") }
        formError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    } }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
        val parsedTime = runCatching { LocalTime.parse(time) }.getOrNull()
        if (parsedDate == null || (!allDay && parsedTime == null)) formError = "Введите корректные дату и время"
        else save(title.trim(), parsedDate, parsedTime ?: LocalTime.MIDNIGHT, duration, allDay)
    }) { Text("Создать") } }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}

@Composable
private fun SettingsScreen(repository: LifeRepository, snapshot: MobileSnapshot, themeMode: String, setThemeMode: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var disconnectPrompt by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Подключение", style = MaterialTheme.typography.titleMedium)
        Text(repository.baseUrl(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(if (snapshot.connected) "Онлайн" else "Отключено"); Text("${snapshot.pending} в очереди", style = MaterialTheme.typography.bodySmall) }
        if (snapshot.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { repository.sync() } }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Повторить") }
            OutlinedButton(onClick = { disconnectPrompt = true }) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("Отключить") }
        }
        Divider()
        Text("Вид", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(themeMode == "system", { setThemeMode("system") }, label = { Text("Системная") })
            FilterChip(themeMode == "light", { setThemeMode("light") }, label = { Text("Светлая") })
            FilterChip(themeMode == "dark", { setThemeMode("dark") }, label = { Text("Тёмная") })
        }
        Divider()
        Text("Виджеты", style = MaterialTheme.typography.titleMedium)
        Text("Добавьте нужные панели на главный экран", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetPinButton(context, "Входящие", "com.personallifeos.mobile.widgets.InboxWidgetReceiver")
            WidgetPinButton(context, "День", "com.personallifeos.mobile.widgets.AgendaWidgetReceiver")
            WidgetPinButton(context, "Месяц", "com.personallifeos.mobile.widgets.MonthWidgetReceiver")
        }
    }
    if (disconnectPrompt) AlertDialog(
        onDismissRequest = { disconnectPrompt = false },
        title = { Text("Отключить аккаунт?") },
        text = { Text(if (snapshot.pending > 0) "В очереди ${snapshot.pending} несинхронизированных изменений. Их удаление необратимо." else "Локальный снимок будет удалён.") },
        confirmButton = {
            TextButton(onClick = {
                disconnectPrompt = false
                scope.launch { repository.disconnect(discardPending = snapshot.pending > 0) }
            }) { Text(if (snapshot.pending > 0) "Удалить очередь и отключить" else "Отключить") }
        },
        dismissButton = { TextButton(onClick = { disconnectPrompt = false }) { Text("Отмена") } },
    )
}

@Composable
private fun WidgetPinButton(context: Context, label: String, receiverName: String) {
    OutlinedButton(onClick = {
        runCatching {
            val receiver = Class.forName(receiverName)
            val info = AppWidgetManager.getInstance(context).installedProviders.firstOrNull { it.provider.packageName == context.packageName && it.provider.className == receiver.name }
            if (info != null && AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported) AppWidgetManager.getInstance(context).requestPinAppWidget(info.provider, null, null)
        }
    }, modifier = Modifier.height(48.dp)) { Icon(Icons.Default.Widgets, null); Spacer(Modifier.width(4.dp)); Text(label) }
}

private fun parseDate(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now(CalendarProjection.zone) }
private fun parseTime(value: String): LocalTime = runCatching { LocalTime.parse(value) }.getOrElse { LocalTime.of(9, 0) }
