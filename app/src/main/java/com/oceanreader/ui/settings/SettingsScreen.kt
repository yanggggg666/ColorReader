package com.oceanreader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oceanreader.data.SettingsStore
import com.oceanreader.data.model.DarkMode
import com.oceanreader.data.model.FontFamilyOption
import com.oceanreader.data.model.PageTurnMode
import com.oceanreader.data.model.ShelfSort
import com.oceanreader.ui.components.AuroraTopBar
import com.oceanreader.ui.components.SectionHeader
import com.oceanreader.ui.components.SettingRow
import com.oceanreader.ui.theme.AccentGradients
import com.oceanreader.ui.theme.PagePalettesDark
import com.oceanreader.ui.theme.PagePalettesLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    settings: SettingsStore
) {
    val prefs by settings.prefs.collectAsState(initial = com.oceanreader.data.Settings())
    val scope = rememberCoroutineScope()

    /** Runs a suspending setter from a non-composable callback. */
    fun write(block: suspend () -> Unit) {
        scope.launch { block.invoke() }
    }

    Scaffold(
        topBar = { AuroraTopBar(title = "设置", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("外观")

            SettingRow(
                title = "主题模式",
                subtitle = listOf("跟随系统", "浅色", "深色")[prefs.darkMode.ordinal],
                trailing = {
                    SegmentedButton(
                        options = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = prefs.darkMode.ordinal,
                        onSelect = { write { settings.setThemeMode(DarkMode.entries[it]) } }
                    )
                }
            )

            SettingRow(
                title = "强调色",
                subtitle = AccentGradients.getOrNull(prefs.accentIndex)?.name ?: "默认",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentGradients.forEachIndexed { index, gradient ->
                            FilledIconButton(
                                onClick = { write { settings.setAccent(index) } },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = gradient.start
                                )
                            ) {
                                if (prefs.accentIndex == index) {
                                    Icon(Icons.Default.Check, null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
            )

            SettingRow(
                title = "动态取色",
                subtitle = "跟随系统壁纸颜色（Android 12+）",
                trailing = {
                    Switch(
                        checked = prefs.dynamicColor,
                        onCheckedChange = { v -> write { settings.setDynamicColor(v) } }
                    )
                }
            )

            HorizontalDivider()

            SectionHeader("阅读")

            SettingRow(
                title = "翻页方式",
                subtitle = prefs.pageTurn.label,
                trailing = {
                    SegmentedButton(
                        options = PageTurnMode.entries.map { it.label },
                        selectedIndex = prefs.pageTurn.ordinal,
                        onSelect = { write { settings.setTurnMode(PageTurnMode.entries[it]) } }
                    )
                }
            )

            SettingRow(
                title = "字体",
                subtitle = prefs.fontFamily.label,
                trailing = {
                    SegmentedButton(
                        options = FontFamilyOption.entries.map { it.label },
                        selectedIndex = FontFamilyOption.entries.indexOf(prefs.fontFamily),
                        onSelect = { write { settings.setFontFamily(FontFamilyOption.entries[it]) } }
                    )
                }
            )

            SettingRow(
                title = "字号",
                subtitle = "${prefs.fontSize}sp",
                trailing = {
                    Slider(
                        value = prefs.fontSize.toFloat(),
                        onValueChange = { write { settings.setFontSize(it.toInt()) } },
                        valueRange = 12f..32f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )

            SettingRow(
                title = "行距",
                subtitle = "${prefs.lineSpacing}%",
                trailing = {
                    Slider(
                        value = prefs.lineSpacing,
                        onValueChange = { write { settings.setLineSpacing(it.toInt()) } },
                        valueRange = 100f..300f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )

            SettingRow(
                title = "段间距",
                subtitle = "${prefs.paragraphSpacing}dp",
                trailing = {
                    Slider(
                        value = prefs.paragraphSpacing.toFloat(),
                        onValueChange = { write { settings.setParaSpacing(it.toInt()) } },
                        valueRange = 0f..40f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )

            SettingRow(
                title = "页边距",
                subtitle = "水平 ${prefs.horizontalPadding}dp",
                trailing = {
                    Slider(
                        value = prefs.horizontalPadding.toFloat(),
                        onValueChange = { write { settings.setMarginH(it.toInt()) } },
                        valueRange = 0f..80f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )

            SettingRow(
                title = "首行缩进",
                subtitle = "${prefs.firstLineIndent} 字符",
                trailing = {
                    SegmentedButton(
                        options = listOf("0", "1", "2", "3", "4"),
                        selectedIndex = prefs.firstLineIndent,
                        onSelect = { write { settings.setIndent(it) } }
                    )
                }
            )

            HorizontalDivider()

            SectionHeader("页面底色")

            SettingRow(
                title = "浅色模式",
                subtitle = PagePalettesLight.find { it.id == prefs.pagePalette }?.name ?: "纸白",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PagePalettesLight.forEach { palette ->
                            FilledIconButton(
                                onClick = { write { settings.setPagePalette(palette.id) } },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = palette.background
                                )
                            ) {
                                if (prefs.pagePalette == palette.id) {
                                    Icon(Icons.Default.Check, null, tint = palette.text)
                                }
                            }
                        }
                    }
                }
            )

            SettingRow(
                title = "深色模式",
                subtitle = PagePalettesDark.find { it.id == prefs.darkPagePalette }?.name ?: "夜阑",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PagePalettesDark.forEach { palette ->
                            FilledIconButton(
                                onClick = { write { settings.setDarkPagePalette(palette.id) } },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = palette.background
                                )
                            ) {
                                if (prefs.darkPagePalette == palette.id) {
                                    Icon(Icons.Default.Check, null, tint = palette.text)
                                }
                            }
                        }
                    }
                }
            )

            HorizontalDivider()

            SectionHeader("朗读")

            SettingRow(
                title = "语速",
                subtitle = String.format("%.1fx", prefs.ttsSpeed),
                trailing = {
                    Slider(
                        value = prefs.ttsSpeed,
                        onValueChange = { write { settings.setTtsSpeed(it) } },
                        valueRange = 0.3f..3f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )

            SettingRow(
                title = "音调",
                subtitle = String.format("%.1fx", prefs.ttsPitch),
                trailing = {
                    Slider(
                        value = prefs.ttsPitch,
                        onValueChange = { write { settings.setTtsPitch(it) } },
                        valueRange = 0.5f..2f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )

            SettingRow(
                title = "单词高亮",
                subtitle = "朗读时高亮当前单词",
                trailing = {
                    Switch(
                        checked = prefs.ttsHighlight,
                        onCheckedChange = { v -> write { settings.setTtsHighlight(v) } }
                    )
                }
            )

            SettingRow(
                title = "自动下一章",
                subtitle = "当前章节读完后自动继续",
                trailing = {
                    Switch(
                        checked = prefs.ttsAutoNextChapter,
                        onCheckedChange = { v -> write { settings.setTtsAutoNext(v) } }
                    )
                }
            )

            HorizontalDivider()

            SectionHeader("书架")

            SettingRow(
                title = "排序方式",
                subtitle = prefs.shelfSort.label,
                trailing = {
                    SegmentedButton(
                        options = ShelfSort.entries.map { it.label },
                        selectedIndex = prefs.gridColumns,
                        onSelect = { write { settings.setShelfSort(ShelfSort.entries[it]) } }
                    )
                }
            )

            SettingRow(
                title = "网格列数",
                subtitle = if (prefs.gridColumns == 0) "自动" else "${prefs.gridColumns} 列",
                trailing = {
                    SegmentedButton(
                        options = listOf("自动", "2", "3", "4", "5"),
                        selectedIndex = prefs.gridColumns,
                        onSelect = { write { settings.setGridColumns(it) } }
                    )
                }
            )

            SettingRow(
                title = "显示封面",
                trailing = {
                    Switch(
                        checked = prefs.showCoverGrid,
                        onCheckedChange = { v -> write { settings.setShowCoverGrid(v) } }
                    )
                }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedButton(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.height(36.dp)
    ) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size
                )
            ) {
                Text(label)
            }
        }
    }
}