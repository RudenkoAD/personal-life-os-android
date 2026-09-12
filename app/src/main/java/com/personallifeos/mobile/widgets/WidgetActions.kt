package com.personallifeos.mobile.widgets

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.Action
import com.personallifeos.mobile.data.LifeRepository
import com.personallifeos.mobile.model.Actions
import com.personallifeos.mobile.ui.MainActivity
import com.personallifeos.mobile.work.SyncScheduler

object WidgetActionKeys {
    val CARD_ID = ActionParameters.Key<String>("card_id")
}

fun openMain(
    context: Context,
    screen: String,
    id: String? = null,
    date: String? = null,
    boardId: String? = null,
    columnId: String? = null,
    time: String? = null,
): Intent =
    Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra("screen", screen)
        id?.let { putExtra("id", it) }
        date?.let { putExtra("date", it) }
        boardId?.let { putExtra("captureBoardId", it) }
        columnId?.let { putExtra("captureColumnId", it) }
        time?.let { putExtra("time", it) }
    }

fun openMainAction(
    context: Context,
    screen: String,
    id: String? = null,
    date: String? = null,
    boardId: String? = null,
    columnId: String? = null,
    time: String? = null,
): Action = actionStartActivity(openMain(context, screen, id, date, boardId, columnId, time))

class CompleteCardAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val id = parameters[WidgetActionKeys.CARD_ID] ?: return
        val repository = LifeRepository.get(context)
        // enqueue is local and immediate; sync is intentionally left to the repository's
        // WorkManager scheduler, so a transient network failure cannot erase the card.
        repository.enqueue(Actions.complete(id))
        SyncScheduler.enqueue(context)
        InboxWidget().update(context, glanceId)
        WidgetUpdates.update(context)
    }
}

fun completeAction(cardId: String): Action = actionRunCallback<CompleteCardAction>(
    actionParametersOf(WidgetActionKeys.CARD_ID to cardId),
)
