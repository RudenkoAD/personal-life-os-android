package com.personallifeos.mobile.widgets

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.unit.ColorProvider
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.data.MobileSnapshot
import com.personallifeos.mobile.model.CalendarItem
import com.personallifeos.mobile.model.CalendarProjection
import com.personallifeos.mobile.model.DayEventBlock
import com.personallifeos.mobile.model.DayTimeline
import com.personallifeos.mobile.model.DayTimelineLayout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow

private val SHOW_NIGHT = booleanPreferencesKey("day_show_night")
private val HEADER_DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale("ru"))
private const val HOUR_HEIGHT = 52

class DayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = LifeRepository.get(context)
        repository.snapshot()
        provideContent {
            val snapshot by repository.snapshots.collectAsState()
            val prefs = androidx.glance.currentState<Preferences>()
            val today = LocalDate.now(CalendarProjection.zone)
            val date = WidgetState.day(prefs, today)
            val items = snapshot.state?.let { CalendarProjection.items(it, date, date.plusDays(1)) }.orEmpty()
            GlanceTheme {
                DayContent(snapshot, DayTimelineLayout.layout(date, items), today, prefs[SHOW_NIGHT] == true)
            }
        }
    }
}

@Composable
private fun DayContent(snapshot: MobileSnapshot, timeline: DayTimeline, today: LocalDate, showNight: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(18.dp)
            .background(GlanceTheme.colors.widgetBackground).padding(8.dp),
    ) {
        DayHeader(timeline.date, today)
        if (!snapshot.connected || snapshot.pending > 0 || snapshot.blocked || snapshot.error != null) {
            Text(
                when {
                    !snapshot.connected -> "Оффлайн · кэш"
                    snapshot.blocked -> "Синхронизация заблокирована"
                    snapshot.error != null -> "Кэш · ${snapshot.error}"
                    else -> "В очереди: ${snapshot.pending}"
                },
                style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 1,
            )
        }
        if (snapshot.state == null) {
            when {
                !snapshot.connected -> EmptyAction("Подключите аккаунт", "settings")
                snapshot.error != null -> EmptyAction("Ошибка · откройте настройки", "settings")
                else -> EmptyAction("Календарь загружается…", null)
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                if (timeline.allDay.isNotEmpty()) {
                    item(itemId = -1L) {
                        Text("Весь день", modifier = GlanceModifier.padding(top = 4.dp, bottom = 2.dp),
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary))
                    }
                    timeline.allDay.forEach { item ->
                        item(itemId = stableItemId(item, 0)) { AllDayRow(timeline.date, item) }
                    }
                }
                item(itemId = -2L) { NightToggle(showNight) }
                val firstHour = firstVisibleHour(timeline, showNight)
                (firstHour until 24).forEach { hour ->
                    item(itemId = hour.toLong()) { HourRow(timeline, hour) }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    val context = androidx.glance.LocalContext.current
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", modifier = button("Предыдущий день").clickable(actionRunCallback<PreviousDayAction>()),
            style = TextStyle(fontSize = 24.sp, color = GlanceTheme.colors.primary))
        Text(
            HEADER_DAY.format(date),
            modifier = GlanceModifier.defaultWeight().semantics { contentDescription = "Открыть день" }
                .clickable(openMainAction(context, "calendar_day", date = date.toString())),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
            maxLines = 2,
        )
        Text("Сегодня", modifier = button("Перейти к сегодняшнему дню").clickable(actionRunCallback<TodayAction>()),
            style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.primary), maxLines = 1)
        Text("›", modifier = button("Следующий день").clickable(actionRunCallback<NextDayAction>()),
            style = TextStyle(fontSize = 24.sp, color = GlanceTheme.colors.primary))
        Text("+", modifier = button("Создать событие").clickable(openMainAction(context, "event_create", date = date.toString())),
            style = TextStyle(fontSize = 22.sp, color = GlanceTheme.colors.primary))
    }
}

@Composable
private fun AllDayRow(date: LocalDate, item: CalendarItem) {
    val context = androidx.glance.LocalContext.current
    val target = item.cardId ?: item.id
    val screen = if (item.cardId != null) "task" else "event"
    Text(item.title, modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
        .semantics { contentDescription = "${item.title}, весь день" }
        .clickable(openMainAction(context, screen, target, date.toString())),
        style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurface), maxLines = 2)
}

@Composable
private fun HourRow(timeline: DayTimeline, hour: Int) {
    val context = androidx.glance.LocalContext.current
    val hourStart = hour * 60
    val hourEnd = hourStart + 60
    val events = timeline.timed.filter { it.startMinute < hourEnd && it.endMinute > hourStart }
    val availableWidth = (androidx.glance.LocalSize.current.width - 16.dp - 38.dp).coerceAtLeast(1.dp)
    Row(modifier = GlanceModifier.fillMaxWidth().height(HOUR_HEIGHT.dp), verticalAlignment = Alignment.Top) {
        Text("%02d:00".format(hour), modifier = GlanceModifier.width(38.dp).padding(top = 2.dp),
            style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant))
        Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(bottom = 1.dp)
            .background(GlanceTheme.colors.surface)
            .semantics { contentDescription = "Создать событие в %02d:00".format(hour) }
            .clickable(openMainAction(context, "event_create", date = timeline.date.toString(), time = "%02d:00".format(hour)))) {
            events.forEach { block ->
                val start = maxOf(block.startMinute, hourStart)
                val end = minOf(block.endMinute, hourEnd)
                val top = (start - hourStart) * HOUR_HEIGHT / 60f
                val width = availableWidth / block.columnCount
                Row(modifier = GlanceModifier.fillMaxWidth().height(HOUR_HEIGHT.dp).padding(top = top.dp)) {
                    if (block.column > 0) Spacer(GlanceModifier.width(width * block.column))
                    Box(GlanceModifier.width(width)) {
                        EventBlock(timeline.date, block, (end - start) * HOUR_HEIGHT / 60f, block.startMinute < hourStart)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventBlock(date: LocalDate, block: DayEventBlock, height: Float, continuation: Boolean) {
    val context = androidx.glance.LocalContext.current
    val item = block.item
    val target = item.cardId ?: item.id
    val screen = if (item.cardId != null) "task" else "event"
    val start = minuteText(block.startMinute)
    val end = minuteText(block.endMinute)
    val eventColor = runCatching { Color.parseColor(item.color) }.getOrDefault(Color.GRAY)
    val foreground = if (luminance(eventColor) < 0.48f) Color.WHITE else Color.BLACK
    val backgroundColor = ColorProvider(androidx.compose.ui.graphics.Color(eventColor))
    val textColor = ColorProvider(androidx.compose.ui.graphics.Color(foreground))
    Column(modifier = GlanceModifier.fillMaxWidth().height(height.dp).padding(horizontal = 1.dp)
        .background(backgroundColor).padding(horizontal = 4.dp, vertical = if (height < 26) 0.dp else 1.dp)
        .semantics { contentDescription = "${item.title}, $start–$end" }
        .clickable(openMainAction(context, screen, target, date.toString(), time = start))) {
        Text(if (continuation) "↳ ${item.title}" else item.title, style = TextStyle(fontSize = if (height < 26) 9.sp else 11.sp, fontWeight = FontWeight.Bold, color = textColor), maxLines = if (height < 42 || continuation) 1 else 2)
        if (height >= 30 && !continuation) Text("$start–$end", style = TextStyle(fontSize = 9.sp, color = textColor), maxLines = 1)
    }
}

@Composable
private fun NightToggle(showNight: Boolean) {
    Text(if (showNight) "День" else "24ч", modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp)
        .semantics { contentDescription = if (showNight) "Скрыть ночные часы" else "Показать ночные часы" }
        .clickable(actionRunCallback<ToggleNightAction>()),
        style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.primary))
}

private fun firstVisibleHour(timeline: DayTimeline, showNight: Boolean): Int {
    if (showNight) return 0
    val earliest = timeline.timed.minOfOrNull { it.startMinute / 60 } ?: 8
    return minOf(8, earliest).coerceIn(0, 23)
}

private fun luminance(color: Int): Float {
    fun channel(value: Int): Float {
        val normalized = value / 255f
        return if (normalized <= 0.03928f) normalized / 12.92f else ((normalized + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
    return 0.2126f * channel(Color.red(color)) + 0.7152f * channel(Color.green(color)) + 0.0722f * channel(Color.blue(color))
}

private fun minuteText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
private fun stableItemId(item: CalendarItem, suffix: Int): Long = item.id.hashCode().toLong() * 31 + suffix
private fun button(description: String): GlanceModifier = GlanceModifier.padding(horizontal = 5.dp, vertical = 10.dp)
    .semantics { contentDescription = description }

private suspend fun moveDay(context: Context, id: GlanceId, transform: (LocalDate) -> LocalDate?) {
    val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
    WidgetState.setDay(context, id, transform(WidgetState.day(prefs)))
    DayWidget().update(context, id)
}

class PreviousDayAction : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = moveDay(context, glanceId) { it.minusDays(1) } }
class NextDayAction : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = moveDay(context, glanceId) { it.plusDays(1) } }
class TodayAction : ActionCallback { override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = moveDay(context, glanceId) { null } }
class ToggleNightAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        androidx.glance.appwidget.state.updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply { this[SHOW_NIGHT] = !(this[SHOW_NIGHT] ?: false) }
        }
        DayWidget().update(context, glanceId)
    }
}
