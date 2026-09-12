package com.personallifeos.mobile.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.action.clickable
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.data.MobileSnapshot
import com.personallifeos.mobile.model.CalendarItem
import com.personallifeos.mobile.model.CalendarProjection
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AgendaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = LifeRepository.get(context)
        repository.snapshot()
        provideContent {
            val snapshot by repository.snapshots.collectAsState()
            val zone = CalendarProjection.zone
            val today = LocalDate.now(zone)
            val items = snapshot.state?.let { state ->
                CalendarProjection.items(state, today, today.plusDays(14))
                    .sortedWith(compareBy<CalendarItem> { it.start }.thenBy { it.title })
            }.orEmpty()
            GlanceTheme { AgendaContent(snapshot, items, zone, today) }
        }
    }
}

@Composable
private fun AgendaContent(
    snapshot: MobileSnapshot,
    items: List<CalendarItem>,
    zone: ZoneId,
    today: LocalDate,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(GlanceTheme.colors.widgetBackground).padding(10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Расписание",
                modifier = GlanceModifier.defaultWeight().clickable(openMainAction(androidx.glance.LocalContext.current, "calendar")),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
            )
            Text(
                "+",
                modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Создать событие" }
                    .clickable(openMainAction(androidx.glance.LocalContext.current, "event_create", date = today.toString())),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary),
            )
            Text(
                "↻",
                modifier = GlanceModifier.padding(horizontal = 9.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Обновить агенду" }
                    .clickable(actionRunCallback<RefreshWidgetAction>()),
                style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.primary),
            )
        }
        if (!snapshot.connected || snapshot.pending > 0 || snapshot.blocked || snapshot.error != null) {
            Text(
                when {
                    !snapshot.connected -> "Оффлайн · показан кэш"
                    snapshot.blocked -> "Синхронизация заблокирована — откройте приложение"
                    snapshot.error != null -> "Кэш · ${snapshot.error}"
                    else -> "В очереди: ${snapshot.pending}"
                },
                style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 2,
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        when {
            !snapshot.connected && snapshot.state == null -> EmptyAction("Подключите аккаунт", "settings")
            snapshot.state == null && snapshot.error != null -> EmptyAction("Ошибка · откройте настройки", "settings")
            snapshot.state == null -> EmptyAction("Загрузка…", null)
            items.isEmpty() -> EmptyAction("Ближайшие 14 дней свободны", "calendar")
            else -> {
                val groups = items.groupBy {
                    val startDate = it.start.atZone(zone).toLocalDate()
                    if (startDate.isBefore(today)) today else startDate
                }
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    groups.toSortedMap().forEach { (date, dayItems) ->
                        item(itemId = date.toEpochDay()) {
                            Text(
                                dayLabel(date, today),
                                modifier = GlanceModifier.padding(top = 4.dp, bottom = 2.dp)
                                    .clickable(openMainAction(androidx.glance.LocalContext.current, "calendar", date = date.toString())),
                                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GlanceTheme.colors.primary),
                            )
                        }
                        dayItems.forEach { calendarItem ->
                            item(itemId = calendarItem.id.hashCode().toLong()) { AgendaRow(calendarItem, zone) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(item: CalendarItem, zone: ZoneId) {
    val context = androidx.glance.LocalContext.current
    val date = item.start.atZone(zone).toLocalDate().toString()
    val target = item.cardId ?: item.id
    val screen = if (item.cardId != null) "task" else "event"
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 8.dp)
            .clickable(openMainAction(context, screen, target, date)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (item.allDay) "весь день" else TIME.format(item.start.atZone(zone)),
            modifier = GlanceModifier.padding(end = 8.dp),
            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
            maxLines = 1,
        )
        Text(
            item.title,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
            maxLines = 3,
        )
    }
}

private val RU_LOCALE = Locale("ru")
private val TIME = DateTimeFormatter.ofPattern("HH:mm", RU_LOCALE)
private val DAY = DateTimeFormatter.ofPattern("EEE, d MMM", RU_LOCALE)

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Сегодня · ${DAY.format(date)}"
    today.plusDays(1) -> "Завтра · ${DAY.format(date)}"
    else -> DAY.format(date)
}
