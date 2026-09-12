package com.personallifeos.mobile.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.model.Board
import com.personallifeos.mobile.model.BoardColumn
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Per-instance Inbox filter: all inbox cards, a board, or one board column. */
class WidgetConfigActivity : ComponentActivity() {
    private val scope = MainScope()
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { ConfigContent(::choose) }
            }
        }
    }

    private fun choose(choice: FilterChoice) {
        scope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            WidgetState.setFilter(this@WidgetConfigActivity, glanceId, choice.boardId, choice.columnId, choice.label)
            InboxWidget().update(this@WidgetConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

private data class FilterChoice(val boardId: String?, val columnId: String?, val label: String)

@Composable
private fun ConfigContent(onChoose: (FilterChoice) -> Unit) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<FilterChoice>?>(null) }
    LaunchedEffect(Unit) {
        choices = runCatching {
            val snapshot = LifeRepository.get(context).snapshot()
            val boards = snapshot.state?.boards.orEmpty()
            buildList {
                add(FilterChoice(null, null, "Входящие"))
                boards.forEach { board ->
                    add(FilterChoice(board.id, null, board.title))
                    board.columns.forEach { column ->
                        add(FilterChoice(board.id, column.id, "${board.title} · ${column.title}"))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Виджет входящих", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Показывать", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        when (val available = choices) {
            null -> Text("Загрузка…")
            emptyList<FilterChoice>() -> Text("Откройте приложение и подключите аккаунт")
            else -> available.forEach { choice ->
                Button(onClick = { onChoose(choice) }, modifier = Modifier.fillMaxWidth()) {
                    Text(choice.label, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
