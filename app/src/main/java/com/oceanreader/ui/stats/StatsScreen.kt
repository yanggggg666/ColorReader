package com.oceanreader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oceanreader.AppContainer
import com.oceanreader.data.db.ReadingDayEntity
import com.oceanreader.ui.components.AppSpacing
import com.oceanreader.ui.components.AuroraTopBar
import com.oceanreader.ui.components.EmptyState
import com.oceanreader.ui.components.ListRow
import com.oceanreader.ui.components.SectionHeader
import com.oceanreader.ui.components.StatTile
import com.oceanreader.ui.theme.LocalAccent
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val stats by container.repository.stats().collectAsState(initial = emptyList())
    val totalMillis by container.repository.totalReadingMillis().collectAsState(initial = 0L)

    val days = stats.size
    val totalChars = stats.sumOf { it.chars }

    /** 明细按日期倒序取最近 30 条；柱状图按日期正序取最近 7 天。 */
    val recent = stats.sortedByDescending { it.day }.take(30)
    val weekly = stats.sortedBy { it.day }.takeLast(7)

    Scaffold(
        topBar = {
            AuroraTopBar(title = "阅读统计", onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Summary tiles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                StatTile(
                    value = formatDuration(totalMillis),
                    label = "总时长",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = "$days 天",
                    label = "阅读天数",
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    value = formatChars(totalChars),
                    label = "总字数",
                    modifier = Modifier.weight(1f)
                )
            }

            // Weekly chart
            SectionHeader("近 7 天")
            if (weekly.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.BarChart,
                    title = "暂无阅读记录",
                    hint = "开始阅读后，这里会显示你每天的阅读时长"
                )
            } else {
                WeeklyChart(
                    stats = weekly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .padding(horizontal = AppSpacing.screen)
                )
            }

            // Recent records — the section is hidden entirely without data.
            if (recent.isNotEmpty()) {
                SectionHeader("明细")
                recent.forEach { day ->
                    ListRow(
                        title = day.day,
                        subtitle = "${formatDuration(day.millis)} · ${formatChars(day.chars)}"
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.xxl))
        }
    }
}

/** 毫秒时长格式化为「X小时Y分」，不足一小时显示「Y分钟」。 */
private fun formatDuration(millis: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}

/** 字数格式化为「X.X 万字」或「X 字」。 */
private fun formatChars(chars: Long): String =
    if (chars >= 10_000) "${"%.1f".format(chars / 10_000.0)} 万字" else "$chars 字"

@Composable
private fun WeeklyChart(
    stats: List<ReadingDayEntity>,
    modifier: Modifier = Modifier
) {
    val accent = LocalAccent.current
    val maxMillis = stats.maxOfOrNull { it.millis }?.coerceAtLeast(1L) ?: 1L

    Canvas(modifier = modifier) {
        val paint = android.graphics.Paint().apply {
            color = accent.start.toArgb()
            style = android.graphics.Paint.Style.FILL
        }
        val barWidth = size.width / (stats.size * 2f)
        val gap = barWidth
        stats.forEachIndexed { index, stat ->
            val height = (stat.millis.toFloat() / maxMillis) * size.height * 0.85f
            val x = index * (barWidth + gap) + gap / 2
            drawContext.canvas.nativeCanvas.drawRect(
                x, size.height - height,
                x + barWidth, size.height,
                paint
            )
        }
    }
}
