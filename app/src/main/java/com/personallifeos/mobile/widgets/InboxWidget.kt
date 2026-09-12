package com.personallifeos.mobile.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.action.clickable
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.model.Card
import com.personallifeos.mobile.model.Placement
import com.personallifeos.mobile.work.SyncScheduler

class InboxWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = LifeRepository.get(context)
        repository.snapshot()
        provideContent {
            val snapshot by repository.snapshots.collectAsState()
            val prefs = androidx.glance.currentState<Preferences>()
            val boardId = WidgetState.boardId(prefs)
            val columnId = WidgetState.columnId(prefs)
            val cards = snapshot.state?.cards.orEmpty()
                .asSequence()
                .filter { card ->
                    !card.done && !card.archived &&
                        if (boardId == null) {
                            card.placement == Placement.inbox
                        } else {
                            card.placement == Placement.board &&
                                card.boardId == boardId && (columnId == null || card.columnId == columnId)
                        }
                }
                .take(40)
                .toList()
            GlanceTheme { InboxContent(prefs, cards, snapshot, boardId, columnId) }
        }
    }
}

@Composable
private fun InboxContent(
    prefs: Preferences,
    cards: List<Card>,
    snapshot: com.personallifeos.mobile.data.MobileSnapshot,
    boardId: String?,
    columnId: String?,
) {
    val label = WidgetState.label(prefs)
    Column(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(18.dp).background(GlanceTheme.colors.widgetBackground).padding(10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = GlanceModifier.defaultWeight().clickable(openMainAction(androidx.glance.LocalContext.current, "inbox", boardId = boardId, columnId = columnId)),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
            )
            Box(
                modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Добавить задачу" }
                    .clickable(actionStartActivity(openMain(androidx.glance.LocalContext.current, "capture", boardId = boardId, columnId = columnId))),
                contentAlignment = Alignment.Center,
            ) { Text("+", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary)) }
            Box(
                modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 14.dp)
                    .semantics { contentDescription = "Обновить входящие" }
                    .clickable(actionRunCallback<RefreshWidgetAction>()),
                contentAlignment = Alignment.Center,
            ) { Text("↻", style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.primary)) }
        }
        if (!snapshot.connected || snapshot.pending > 0 || snapshot.blocked || snapshot.error != null) {
            val status = when {
                !snapshot.connected -> "Оффлайн · показан кэш"
                snapshot.blocked -> "Синхронизация заблокирована — откройте приложение"
                snapshot.error != null -> "Локально сохранено · ${snapshot.error}"
                else -> "В очереди: ${snapshot.pending}"
            }
            Text(status, style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 2)
        }
        Spacer(GlanceModifier.height(4.dp))
        when {
            !snapshot.connected && snapshot.state == null -> EmptyAction("Подключите аккаунт", "settings")
            snapshot.state == null && snapshot.error != null -> EmptyAction("Ошибка · откройте настройки", "settings")
            snapshot.state == null -> EmptyAction("Загрузка…", null)
            cards.isEmpty() -> EmptyAction("Пусто · добавить задачу", "capture", boardId, columnId)
            else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(cards, itemId = { it.id.hashCode().toLong() }) { card ->
                    CardRow(card)
                }
            }
        }
    }
}

@Composable
private fun CardRow(card: Card) {
    val context = androidx.glance.LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.padding(end = 7.dp, top = 15.dp, bottom = 15.dp)
                .semantics { contentDescription = "Завершить: ${card.title}" }
                .clickable(completeAction(card.id)),
            contentAlignment = Alignment.Center,
        ) { Text("□", style = TextStyle(fontSize = 19.sp, color = GlanceTheme.colors.primary)) }
        Text(
            card.title,
            modifier = GlanceModifier.defaultWeight().clickable(openMainAction(context, "task", card.id)),
            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
            maxLines = 3,
        )
    }
}

class RefreshWidgetAction : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        // Network work belongs to WorkManager; a widget callback only requests it.
        SyncScheduler.enqueue(context)
        WidgetUpdates.update(context)
    }
}
