package com.oceanreader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oceanreader.AppContainer
import com.oceanreader.ui.components.AuroraTopBar
import com.oceanreader.ui.components.SectionHeader
import com.oceanreader.ui.theme.LocalAccent
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { AppContainer.from(context) }
    val stats by container.repository.stats().collectAsState(initial = emptyList())
    val totalMillis by container.repository.totalReadingMillis().collectAsState(initial = 0L)

    val totalHours = TimeUnit.MILLISECONDS.toMinutes(totalMillis) / 60.0
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
    val days = stats.size

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
            // Summary cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "总时长",
                    value = "${totalHours.toInt()}h ${totalMinutes.toInt()}m",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "阅读天数",
                    value = "$days 天",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "总字数",
                    value = "${stats.sumOf { it.chars } / 1000}k",
                    modifier = Modifier.weight(1f)
                )
            }

            // Weekly chart
            SectionHeader("近 7 天")
            if (stats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无阅读记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                WeeklyChart(
                    stats = stats.takeLast(7),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .padding(horizontal = 16.dp)
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeeklyChart(
    stats: List<com.oceanreader.data.db.ReadingDayEntity>,
    modifier: Modifier = Modifier
) {
    val accent = LocalAccent.current
    val maxMillis = stats.maxOfOrNull { it.millis }?.coerceAtLeast(1L) ?: 1L

    Canvas(modifier = modifier) {
        val barWidth = size.width / (stats.size * 2f)
        val gap = barWidth
        stats.forEachIndexed { index, stat ->
            val height = (stat.millis.toFloat() / maxMillis) * size.height * 0.85f
            val x = index * (barWidth + gap) + gap / 2
            drawContext.canvas.nativeCanvas.apply {
                drawRect(
                    x, size.height - height,
                    x + barWidth, size.height,
                    android.graphics.Paint().apply {
                        color = accent.start.hashCode()
                        style = android.graphics.Paint.Style.FILL
                    }
                )
            }
        }
    }
}