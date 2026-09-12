package com.personallifeos.mobile.widgets

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.personallifeos.mobile.model.CalendarProjection
import java.time.LocalDate

/** State which belongs to one widget instance, rather than to the account. */
object WidgetState {
    val FILTER_BOARD_ID = stringPreferencesKey("filter_board_id")
    val FILTER_COLUMN_ID = stringPreferencesKey("filter_column_id")
    val FILTER_LABEL = stringPreferencesKey("filter_label")
    val MONTH_YEAR = longPreferencesKey("month_year")
    val MONTH_MONTH = longPreferencesKey("month_month")
    val SEEN = booleanPreferencesKey("seen")

    const val ALL = "__all__"

    fun boardId(prefs: Preferences): String? = prefs[FILTER_BOARD_ID]?.takeUnless { it == ALL }
    fun columnId(prefs: Preferences): String? = prefs[FILTER_COLUMN_ID]?.takeUnless { it == ALL }
    fun label(prefs: Preferences): String = prefs[FILTER_LABEL] ?: "Входящие"

    fun month(prefs: Preferences, today: LocalDate = LocalDate.now(CalendarProjection.zone)): LocalDate {
        val year = prefs[MONTH_YEAR]?.toInt() ?: today.year
        val month = prefs[MONTH_MONTH]?.toInt()?.coerceIn(1, 12) ?: today.monthValue
        return LocalDate.of(year, month, 1)
    }

    suspend fun setFilter(
        context: Context,
        id: GlanceId,
        boardId: String?,
        columnId: String?,
        label: String,
    ) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply {
                this[FILTER_BOARD_ID] = boardId ?: ALL
                this[FILTER_COLUMN_ID] = columnId ?: ALL
                this[FILTER_LABEL] = label
                this[SEEN] = true
            }
        }
    }

    suspend fun setMonth(context: Context, id: GlanceId, month: LocalDate) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            prefs.toMutablePreferences().apply {
                this[MONTH_YEAR] = month.year.toLong()
                this[MONTH_MONTH] = month.monthValue.toLong()
                this[SEEN] = true
            }
        }
    }
}
