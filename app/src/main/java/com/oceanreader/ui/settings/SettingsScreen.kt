package com.oceanreader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oceanreader.data.DarkMode
import com.oceanreader.data.FontFamilyOption
import com.oceanreader.data.PageTurnMode
import com.oceanreader.data.SettingsStore
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
    val prefs by settings.prefs.collectAsState()

    Scaffold(
        topBar = {
            AuroraTopBar(title = "设置", onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Appearance
            SectionHeader("外观")
            SettingRow(
                title = "主题模式",
                subtitle = when (prefs.themeMode) {
                    DarkMode.SYSTEM -> "跟随系统"
                    DarkMode.LIGHT -> "浅色"
                    DarkMode.DARK -> "深色"
                },
                trailing = {
                    SegmentedButton(
                        options = DarkMode.entries.map { it.label },
                        selectedIndex = prefs.themeMode.ordinal,
                        onSelect = { settings.setThemeMode(DarkMode.entries[it]) }
                    )
                }
            )
            SettingRow(
                title = "强调色",
                subtitle = "应用的主题色调",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentGradients.forEachIndexed { index, gradient ->
                            FilledIconButton(
                                onClick = { settings.setAccent(index) },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = gradient.start
                                )
                            ) {
                                if (prefs.accentIndex == index) {
                                    Icon(Icons.Default.Check, null, tint = androidx.compose.ui.graphics.Color.White)
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
                        onCheckedChange = { settings.setDynamicColor(it) }
                    )
                }
            )

            HorizontalDivider()

            // Reading
            SectionHeader("阅读")
            SettingRow(
                title = "翻页方式",
                subtitle = when (prefs.pageTurn) {
                    PageTurnMode.SLIDE -> "平滑翻页"
                    PageTurnMode.NONE -> "无动画"
                    PageTurnMode.COVER -> "覆盖"
                    PageTurnMode.SCROLL -> "纵向滚动"
                },
                trailing = {
                    SegmentedButton(
                        options = PageTurnMode.entries.map { it.label },
                        selectedIndex = prefs.pageTurn.ordinal,
                        onSelect = { settings.setPageTurn(PageTurnMode.entries[it]) }
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
                        onSelect = { settings.setFontFamily(FontFamilyOption.entries[it]) }
                    )
                }
            )
            SettingRow(
                title = "字号",
                subtitle = "${prefs.fontSize}sp",
                trailing = {
                    Slider(
                        value = prefs.fontSize.toFloat(),
                        onValueChange = { settings.setFontSize(it.toInt()) },
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
                        value = prefs.lineSpacing.toFloat(),
                        onValueChange = { settings.setLineSpacing(it.toInt()) },
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
                        onValueChange = { settings.setParaSpacing(it.toInt()) },
                        valueRange = 0f..40f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )
            SettingRow(
                title = "页边距",
                subtitle = "水平 ${prefs.horizontalPadding}dp · 垂直 ${prefs.verticalPadding}dp",
                trailing = {
                    Slider(
                        value = prefs.horizontalPadding.toFloat(),
                        onValueChange = { settings.setMarginH(it.toInt()) },
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
                        onSelect = { settings.setIndent(it) }
                    )
                }
            )
            SettingRow(
                title = "加粗正文",
                trailing = {
                    Switch(checked = prefs.boldText, onCheckedChange = { settings.setBold(it) })
                }
            )
            SettingRow(
                title = "两端对齐",
                trailing = {
                    Switch(checked = prefs.justifyText, onCheckedChange = { settings.setJustify(it) })
                }
            )

            HorizontalDivider()

            // Page palette
            SectionHeader("页面底色")
            SettingRow(
                title = "浅色模式",
                subtitle = PagePalettesLight.find { it.id == prefs.pagePalette }?.name ?: "纸白",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PagePalettesLight.forEachIndexed { index, palette ->
                            FilledIconButton(
                                onClick = { settings.setPagePalette(palette.id) },
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
                        PagePalettesDark.forEachIndexed { index, palette ->
                            FilledIconButton(
                                onClick = { settings.setDarkPagePalette(palette.id) },
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

            // TTS
            SectionHeader("朗读")
            SettingRow(
                title = "语速",
                subtitle = String.format("%.1fx", prefs.ttsSpeed),
                trailing = {
                    Slider(
                        value = prefs.ttsSpeed,
                        onValueChange = { settings.setTtsSpeed(it) },
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
                        onValueChange = { settings.setTtsPitch(it) },
                        valueRange = 0.5f..2f,
                        modifier = Modifier.width(120.dp)
                    )
                }
            )
            SettingRow(
                title = "单词高亮",
                subtitle = "朗读时高亮当前单词",
                trailing = {
                    Switch(checked = prefs.ttsHighlight, onCheckedChange = { settings.setTtsHighlight(it) })
                }
            )
            SettingRow(
                title = "自动下一章",
                subtitle = "当前章节读完后自动继续",
                trailing = {
                    Switch(checked = prefs.ttsAutoNextChapter, onCheckedChange = { settings.setTtsAutoNext(it) })
                }
            )

            HorizontalDivider()

            // Shelf
            SectionHeader("书架")
            SettingRow(
                title = "排序方式",
                subtitle = when (prefs.shelfSort) {
                    com.oceanreader.data.ShelfSort.RECENT -> "最近阅读"
                    com.oceanreader.data.ShelfSort.ADDED -> "加入时间"
                    com.oceanreader.data.ShelfSort.TITLE -> "书名"
                    com.oceanreader.data.ShelfSort.AUTHOR -> "作者"
                    com.oceanreader.data.ShelfSort.PROGRESS -> "阅读进度"
                },
                trailing = {
                    SegmentedButton(
                        options = com.oceanreader.data.ShelfSort.entries.map { it.label },
                        selectedIndex = prefs.shelfSort.ordinal,
                        onSelect = { settings.setShelfSort(com.oceanreader.data.ShelfSort.entries[it]) }
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
                        onSelect = { settings.setGridColumns(it) }
                    )
                }
            )
            SettingRow(
                title = "显示封面",
                trailing = {
                    Switch(checked = prefs.showCoverGrid, onCheckedChange = { settings.setShowCoverGrid(it) })
                }
            )

            HorizontalDivider()

            // Other
            SectionHeader("其他")
            SettingRow(
                title = "导入时复制文件",
                subtitle = "将书籍复制到应用目录，避免原文件被删除后无法阅读",
                trailing = {
                    Switch(checked = prefs.copyOnImport, onCheckedChange = { settings.setCopyOnImport(it) })
                }
            )
            SettingRow(
                title = "阅读统计",
                subtitle = "记录每日阅读时长",
                trailing = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            )
            SettingRow(
                title = "关于",
                subtitle = "潮阅 v1.0.0",
                trailing = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.ChevronRight, null)
                    }
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
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(label)
            }
        }
    }
}