package com.personallifeos.mobile.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.data.MobileSnapshot
import com.personallifeos.mobile.model.CalendarItem
import com.personallifeos.mobile.model.CalendarProjection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

class MonthWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = LifeRepository.get(context)
        repository.snapshot()
        provideContent {
            val snapshot by repository.snapshots.collectAsState()
            val prefs = androidx.glance.currentState<Preferences>()
            val month = WidgetState.month(prefs)
            val yearMonth = YearMonth.from(month)
            val items = snapshot.state?.let { state ->
                CalendarProjection.items(state, yearMonth.atDay(1), yearMonth.plusMonths(1).atDay(1))
            }.orEmpty()
            GlanceTheme { MonthContent(prefs, snapshot, yearMonth, items) }
        }
    }
}

private val RU_LOCALE = Locale("ru")
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", RU_LOCALE)
private val NAV_DELTA = ActionParameters.Key<Int>("month_delta")

@Composable
private fun MonthContent(
    prefs: Preferences,
    snapshot: MobileSnapshot,
    month: YearMonth,
    items: List<CalendarItem>,
) {
    val context = androidx.glance.LocalContext.current
    val today = LocalDate.now(CalendarProjection.zone)
    // Check the half-open item interval against each day boundary. Looking only at
    // start dates misses all-day and multi-day events which began earlier.
    val itemsByDate = buildMap<LocalDate, List<CalendarItem>> {
        var date = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        while (date.isBefore(end)) {
            val dayStart = date.atStartOfDay(CalendarProjection.zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(CalendarProjection.zone).toInstant()
            val overlapping = items.filter { item -> item.start < dayEnd && item.end > dayStart }
            if (overlapping.isNotEmpty()) put(date, overlapping)
            date = date.plusDays(1)
        }
    }
    Column(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(GlanceTheme.colors.widgetBackground).padding(8.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                modifier = GlanceModifier.padding(horizontal = 10.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Предыдущий месяц" }
                    .clickable(actionRunCallback<MonthNavigateAction>(actionParametersOf(NAV_DELTA to -1))),
                style = TextStyle(fontSize = 22.sp, color = GlanceTheme.colors.primary),
            )
            Text(
                MONTH_FORMAT.format(month.atDay(1)).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
            )
            Text(
                "+",
                modifier = GlanceModifier.padding(horizontal = 7.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Создать событие" }
                    .clickable(
                        openMainAction(
                            context,
                            "event_create",
                            date = (if (YearMonth.from(today) == month) today else month.atDay(1)).toString(),
                        ),
                    ),
                style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary),
            )
            Text(
                "Сегодня",
                modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Перейти к текущему месяцу" }
                    .clickable(actionRunCallback<MonthTodayAction>()),
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.primary),
            )
            Text(
                "›",
                modifier = GlanceModifier.padding(horizontal = 10.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Следующий месяц" }
                    .clickable(actionRunCallback<MonthNavigateAction>(actionParametersOf(NAV_DELTA to 1))),
                style = TextStyle(fontSize = 22.sp, color = GlanceTheme.colors.primary),
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
        WeekHeader()
        when {
            !snapshot.connected && snapshot.state == null -> EmptyAction("Подключите аккаунт", "settings")
            snapshot.state == null && snapshot.error != null -> EmptyAction("Ошибка · откройте настройки", "settings")
            snapshot.state == null -> EmptyAction("Загрузка…", null)
            else -> MonthGrid(month, today, itemsByDate)
        }
    }
}

@Composable
private fun WeekHeader() {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        DayOfWeek.values().forEach { day ->
            Text(
                day.getDisplayName(JavaTextStyle.SHORT, RU_LOCALE).take(2),
                modifier = GlanceModifier.defaultWeight().padding(vertical = 2.dp),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, itemsByDate: Map<LocalDate, List<CalendarItem>>) {
    val firstCell = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value - 1).toLong())
    Column(modifier = GlanceModifier.fillMaxSize()) {
        repeat(6) { week ->
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                repeat(7) { offset ->
                    val date = firstCell.plusDays((week * 7 + offset).toLong())
                    DayCell(date, month, today, itemsByDate[date].orEmpty())
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(date: LocalDate, month: YearMonth, today: LocalDate, events: List<CalendarItem>) {
    val context = androidx.glance.LocalContext.current
    val isCurrent = date == today
    val inMonth = YearMonth.from(date) == month
    Box(
        modifier = GlanceModifier.defaultWeight().padding(1.dp)
            .semantics {
                contentDescription = "Открыть ${date.dayOfMonth} " +
                    date.month.getDisplayName(JavaTextStyle.FULL_STANDALONE, RU_LOCALE)
            }
            .clickable(openMainAction(context, "calendar", date = date.toString())),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buildString {
                append(date.dayOfMonth)
                if (events.isNotEmpty()) append("  •")
            },
            modifier = GlanceModifier.padding(vertical = 8.dp, horizontal = 1.dp),
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !inMonth -> GlanceTheme.colors.onSurfaceVariant
                    isCurrent -> GlanceTheme.colors.primary
                    else -> GlanceTheme.colors.onSurface
                },
            ),
            maxLines = 1,
        )
    }
}

class MonthNavigateAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[NAV_DELTA] ?: return
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        WidgetState.setMonth(context, glanceId, WidgetState.month(prefs).plusMonths(delta.toLong()))
        MonthWidget().update(context, glanceId)
    }
}

class MonthTodayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetState.setMonth(context, glanceId, LocalDate.now(CalendarProjection.zone).withDayOfMonth(1))
        MonthWidget().update(context, glanceId)
    }
}
