package com.personallifeos.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personallifeos.mobile.model.CalendarItem
import com.personallifeos.mobile.model.CalendarProjection
import com.personallifeos.mobile.model.DayEventBlock
import com.personallifeos.mobile.model.DayTimelineLayout
import com.personallifeos.mobile.model.LifeState
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val HOUR_HEIGHT = 64
private val DayLabelWidth = 46.dp

@Composable
internal fun DayCalendar(
    state: LifeState,
    date: LocalDate,
    open: (CalendarItem) -> Unit,
    create: (LocalDate, LocalTime) -> Unit,
    scrollRequest: Int = 0,
) {
    val timeline = DayTimelineLayout.layout(date, CalendarProjection.items(state, date, date.plusDays(1)))
    val scroll = rememberScrollState()
    val today = LocalDate.now(CalendarProjection.zone)
    val firstTimed = timeline.timed.minOfOrNull { it.startMinute } ?: 8 * 60
    val density = LocalDensity.current
    var lastScrollRequest by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(date, scrollRequest) {
        val request = "$date:$scrollRequest"
        if (lastScrollRequest == request) return@LaunchedEffect
        val target = if (date == today) LocalTime.now(CalendarProjection.zone).hour * HOUR_HEIGHT else (firstTimed / 60).coerceIn(0, 8) * HOUR_HEIGHT
        scroll.animateScrollTo(with(density) { ((target - HOUR_HEIGHT).coerceAtLeast(0).dp).roundToPx() })
        lastScrollRequest = request
    }
    Column(Modifier.fillMaxSize().testTag("day_timeline")) {
        if (timeline.allDay.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(max = 104.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text("Весь день", Modifier.width(DayLabelWidth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    timeline.allDay.forEach { item ->
                        Surface(Modifier.fillMaxWidth().clickable { open(item) }.testTag("day_event_${item.id}"), color = eventColor(item), shape = MaterialTheme.shapes.small) {
                            Text(item.title, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), maxLines = 2, style = MaterialTheme.typography.labelMedium, color = readableOn(eventColor(item)))
                        }
                    }
                }
            }
            Divider()
        }
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            DayGrid(timeline.timed, date, open, create)
        }
    }
}

@Composable
private fun DayGrid(blocks: List<DayEventBlock>, date: LocalDate, open: (CalendarItem) -> Unit, create: (LocalDate, LocalTime) -> Unit) {
    val density = LocalDensity.current
    val hourHeight = HOUR_HEIGHT.dp
    var currentMinute by remember(date) { mutableStateOf(LocalTime.now(CalendarProjection.zone).hour * 60 + LocalTime.now(CalendarProjection.zone).minute) }
    LaunchedEffect(date) {
        while (true) { currentMinute = LocalTime.now(CalendarProjection.zone).let { it.hour * 60 + it.minute }; delay(60_000) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(hourHeight * 24)) {
        Column {
            repeat(24) { hour ->
                Row(Modifier.height(hourHeight).testTag("day_hour_$hour")) {
                    Text(String.format("%02d:00", hour), Modifier.width(DayLabelWidth).padding(start = 8.dp, top = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface).pointerInput(date, hour) {
                        detectTapGestures { offset ->
                            val minute = (offset.y / with(density) { hourHeight.toPx() } * 60f).roundToInt().coerceIn(0, 59)
                            create(date, LocalTime.of(hour, (minute / 15) * 15))
                        }
                    }) { Divider(Modifier.align(androidx.compose.ui.Alignment.TopCenter)) }
                }
            }
        }
        blocks.forEach { block ->
            val available = maxWidth - DayLabelWidth
            val laneWidth = available / block.columnCount
            val y = (block.startMinute * HOUR_HEIGHT / 60f).dp
            val height = ((block.endMinute - block.startMinute) * HOUR_HEIGHT / 60f).coerceAtLeast(10f).dp
            val x = DayLabelWidth + laneWidth * block.column
            val start = LocalTime.of(block.startMinute / 60, block.startMinute % 60)
            val endText = if (block.endMinute >= 24 * 60) "24:00" else LocalTime.of(block.endMinute / 60, block.endMinute % 60).format(DateTimeFormatter.ofPattern("HH:mm"))
            Surface(Modifier.offset(x, y).width(laneWidth).height(height).padding(horizontal = 1.dp, vertical = 1.dp).clickable { open(block.item) }.testTag("day_event_${block.item.id}").semantics { contentDescription = "${block.item.title}, ${start.format(DateTimeFormatter.ofPattern("HH:mm"))}–$endText" }, color = eventColor(block.item), shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(horizontal = 5.dp, vertical = if (height < 30.dp) 0.dp else 1.dp)) {
                    Text(block.item.title, maxLines = if (height < 60.dp) 1 else 2, style = if (height < 30.dp) MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp) else MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp), color = readableOn(eventColor(block.item)))
                    if (height >= 30.dp) Text("${start.format(DateTimeFormatter.ofPattern("HH:mm"))}–$endText", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp), color = readableOn(eventColor(block.item)))
                }
            }
        }
        if (date == LocalDate.now(CalendarProjection.zone)) {
            val y = (currentMinute * HOUR_HEIGHT / 60f).dp
            Box(Modifier.offset(DayLabelWidth, y).width(maxWidth - DayLabelWidth).height(2.dp).background(MaterialTheme.colorScheme.error).testTag("day_current_time"))
        }
    }
}

private fun eventColor(item: CalendarItem): Color = runCatching { Color(android.graphics.Color.parseColor(item.color)) }.getOrDefault(Color(0xFF6677DD)).copy(alpha = .92f)
private fun readableOn(color: Color): Color = if ((color.red * 299 + color.green * 587 + color.blue * 114) > 500) Color.Black else Color.White
