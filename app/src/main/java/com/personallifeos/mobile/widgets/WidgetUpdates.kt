package com.personallifeos.mobile.widgets

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager

/** Refreshes every live instance after a local snapshot or outbox change. */
object WidgetUpdates {
    suspend fun update(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        refresh(manager, context, InboxWidget())
        refresh(manager, context, AgendaWidget())
        refresh(manager, context, DayWidget())
        refresh(manager, context, MonthWidget())
    }

    private suspend fun refresh(
        manager: GlanceAppWidgetManager,
        context: Context,
        widget: androidx.glance.appwidget.GlanceAppWidget,
    ) {
        val ids = manager.getGlanceIds(widget.javaClass)
        ids.forEach { widget.update(context, it) }
    }
}
