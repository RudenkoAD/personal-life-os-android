package com.personallifeos.mobile.widgets

import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.sp

@Composable
fun EmptyAction(text: String, screen: String?, boardId: String? = null, columnId: String? = null) {
    val modifier = if (screen == null) GlanceModifier else
        GlanceModifier.clickable(openMainAction(LocalContext.current, screen, boardId = boardId, columnId = columnId))
    Column(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 2)
    }
}
