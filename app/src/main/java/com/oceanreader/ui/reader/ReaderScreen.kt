package com.oceanreader.ui.reader

import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oceanreader.data.Settings
import com.oceanreader.data.model.FontFamilyOption
import kotlinx.coroutines.launch
import com.oceanreader.reader.HighlightSpan
import com.oceanreader.reader.ReaderPanel
import com.oceanreader.reader.ReaderStyle
import com.oceanreader.reader.ReaderTypesetter
import com.oceanreader.reader.ReaderUiState
import com.oceanreader.reader.ReaderViewModel
import com.oceanreader.reader.TextPaginator
import com.oceanreader.ui.components.AppSpacing
import com.oceanreader.ui.components.AuroraIconButton
import com.oceanreader.ui.components.SheetHeader
import com.oceanreader.ui.theme.LocalGlass
import com.oceanreader.ui.theme.PagePalettesDark
import com.oceanreader.ui.theme.PagePalettesLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    vm: ReaderViewModel = viewModel(
        factory = ReaderViewModelFactory(
            app = LocalContext.current.applicationContext as android.app.Application,
            bookId = bookId
        )
    )
) {
    val state by vm.ui.collectAsState()
    val speechState by vm.speech.state.collectAsState()
    val speechCursor by vm.speech.cursor.collectAsState()
    val prefs by vm.prefs.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Reading surface
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.error != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    state.error ?: "无法打开",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            ReaderSurface(
                state = state,
                prefs = prefs,
                speechCursor = speechCursor,
                vm = vm,
                onTap = { vm.toggleChrome() },
                onSelection = { text, start, end -> vm.onSelectionChanged(text, start, end) },
                onLongPress = { offset -> vm.onLongPressAt(offset) }
            )
        }

        // Top chrome
        AnimatedVisibility(
            visible = state.chromeVisible && !state.loading,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                title = state.book?.title.orEmpty(),
                chapterTitle = state.chapterTitle,
                onBack = onBack,
                onToc = { vm.setPanel(ReaderPanel.TOC) },
                onMore = { action ->
                    when (action) {
                        ReaderMenuAction.SEARCH -> vm.setPanel(ReaderPanel.SEARCH)
                        ReaderMenuAction.SPEECH -> vm.setPanel(ReaderPanel.SPEECH)
                        ReaderMenuAction.BOOKMARKS -> vm.setPanel(ReaderPanel.BOOKMARK)
                        ReaderMenuAction.NOTES -> vm.setPanel(ReaderPanel.NOTES)
                        ReaderMenuAction.APPEARANCE -> vm.setPanel(ReaderPanel.APPEARANCE)
                    }
                }
            )
        }

        // Bottom chrome
        AnimatedVisibility(
            visible = state.chromeVisible && !state.loading,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                state = state,
                speechState = speechState,
                onPrev = vm::previousPage,
                onNext = vm::nextPage,
                onProgress = { fraction ->
                    if (state.isPaged) {
                        vm.goToPage((fraction * state.pageCount).toInt())
                    } else {
                        vm.goToPercent(fraction)
                    }
                },
                onSpeechToggle = {
                    if (speechState.playing) vm.stopSpeaking() else vm.startSpeaking()
                },
                onSpeechPanel = { vm.setPanel(ReaderPanel.TOC) }
            )
        }

        // Selection toolbar
        if (state.selection.isNotBlank()) {
            val clipboard = LocalClipboardManager.current
            SelectionToolbar(
                text = state.selection,
                onHighlight = { vm.highlightSelection() },
                onUnderline = { vm.highlightSelection(underline = true) },
                onNote = { vm.setPanel(ReaderPanel.NOTES) },
                onCopy = {
                    clipboard.setText(AnnotatedString(state.selection))
                    vm.clearSelection()
                },
                onClear = vm::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Panels
        if (state.panel != ReaderPanel.NONE) {
            ReaderPanelSheet(
                panel = state.panel,
                state = state,
                vm = vm,
                onDismiss = { vm.setPanel(ReaderPanel.NONE) }
            )
        }
    }
}

// ------------------------------------------------------------------ surface

@Composable
private fun ReaderSurface(
    state: ReaderUiState,
    prefs: Settings,
    speechCursor: com.oceanreader.tts.SpeechCursor,
    vm: ReaderViewModel,
    onTap: () -> Unit,
    onSelection: (String, Int, Int) -> Unit,
    onLongPress: (Int) -> Unit
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme

    // The reading surface uses the user's page palette (纸白/羊皮/护眼/…),
    // falling back to the theme colours when nothing matches.
    val dark = scheme.background.luminance() < 0.5f
    val palette = remember(prefs.pagePalette, prefs.darkPagePalette, dark) {
        val id = if (dark) prefs.darkPagePalette else prefs.pagePalette
        (if (dark) PagePalettesDark else PagePalettesLight).firstOrNull { it.id == id }
    }

    val typeface = when (prefs.fontFamily) {
        FontFamilyOption.SANS -> Typeface.SANS_SERIF
        FontFamilyOption.SERIF -> Typeface.SERIF
        FontFamilyOption.ROUNDED -> Typeface.create("sans-serif", Typeface.NORMAL)
        FontFamilyOption.MONO -> Typeface.MONOSPACE
    }

    val style = remember(
        prefs.fontSize, prefs.lineSpacing, prefs.horizontalPadding, prefs.verticalPadding,
        prefs.firstLineIndent, prefs.boldText, prefs.justifyText, prefs.fontFamily,
        prefs.paragraphSpacing, density, scheme, palette
    ) {
        val codeBg = palette?.secondary?.toArgb() ?: scheme.onSurface.toArgb()
        ReaderStyle(
            fontSizePx = with(density) { prefs.fontSize.sp.toPx() },
            // CJK never goes below ~1.35 line height: tighter spacing clips
            // character tops and bottoms.
            lineSpacing = prefs.lineSpacing.coerceAtLeast(1.35f),
            marginHorizontalPx = with(density) { prefs.horizontalPadding.dp.toPx() },
            marginVerticalPx = with(density) { prefs.verticalPadding.dp.toPx() },
            indentPx = with(density) { (prefs.fontSize * prefs.firstLineIndent).sp.toPx() },
            bold = prefs.boldText,
            justify = prefs.justifyText,
            backgroundColor = palette?.background?.toArgb() ?: scheme.background.toArgb(),
            textColor = palette?.text?.toArgb() ?: scheme.onBackground.toArgb(),
            sentenceColor = palette?.secondary?.toArgb() ?: 0xFFFFB74D.toInt(),
            wordColor = 0xFFFF9800.toInt(),
            typeface = typeface,
            paragraphSpacingPx = with(density) { prefs.paragraphSpacing.dp.toPx() },
            headingColor = palette?.secondary?.toArgb() ?: scheme.onBackground.toArgb(),
            codeBackground = (0x22 shl 24) or (codeBg and 0x00FFFFFF)
        )
    }

    val paint = remember(style) {
        android.text.TextPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
            textSize = style.fontSizePx
            color = style.textColor
            isFakeBoldText = style.bold
            // `typeface` resolves to the outer preview value without `this`.
            this.typeface = style.typeface
        }
    }

    // Block-stream typesetting: headings, paragraphs and code blocks get their
    // own size and spacing without changing a single character offset.
    val styledText = remember(state.chapterText, state.chapterIndex, style, state.chapterTitle) {
        ReaderTypesetter.layout(state.chapterText, style, state.chapterTitle)
    }

    val paginator = remember { TextPaginator() }

    val highlightSpans = remember(state.highlights, state.chapterIndex) {
        state.highlights
            .filter { it.chapterIndex == state.chapterIndex }
            .map {
                HighlightSpan(
                    start = it.start,
                    end = it.end,
                    color = HighlightColors.getOrElse(it.colorIndex) { HighlightColors[0] },
                    underline = it.kind == 1
                )
            }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(style.backgroundColor))
            .pointerInput(Unit) {
                // Mainstream tap zones: left/right quarter turns the page,
                // the middle band toggles the immersive chrome.
                detectTapGestures { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    when {
                        offset.x < w * 0.25f -> vm.previousPage()
                        offset.x > w * 0.75f -> vm.nextPage()
                        else -> onTap()
                    }
                }
            }
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Paginate whenever the chapter, the viewport or the style changes.
        LaunchedEffect(styledText, paint, widthPx, heightPx, style.lineSpacing, style.justify, style.marginHorizontalPx, style.marginVerticalPx) {
            val contentW = (widthPx - style.marginHorizontalPx * 2).toInt().coerceAtLeast(1)
            val contentH = (heightPx - style.marginVerticalPx * 2).toInt().coerceAtLeast(1)
            paginator.paginate(
                text = styledText,
                paint = paint,
                width = contentW,
                height = contentH,
                spacingMultiplier = style.lineSpacing,
                spacingAdd = 0,
                justify = style.justify
            )
            vm.reportPagination(paginator.pageCount)
            val restore = state.restoreOffset
            if (restore > 0 && restore < styledText.length) {
                val page = paginator.pageOf(restore)
                vm.setPage(page, restore)
            }
        }

        // Follow the voice: keep the spoken sentence on screen (跟读).
        LaunchedEffect(speechCursor.chapterIndex, speechCursor.sentenceStart, state.chapterIndex, state.loading) {
            if (state.loading || !speechCursor.hasSentence) return@LaunchedEffect
            if (speechCursor.chapterIndex != state.chapterIndex) {
                vm.goToChapter(speechCursor.chapterIndex)
            } else {
                val target = paginator.pageOf(speechCursor.sentenceStart)
                if (target != state.pageIndex) {
                    vm.setPage(target, paginator.startOf(target))
                }
            }
        }

        if (state.isPaged) {
            PdfPageView(
                filePath = state.book?.filePath.orEmpty(),
                page = state.pageIndex,
                pageCount = state.pageCount,
                onPageChanged = { page -> vm.onPageRendered(page, 0) }
            )
        } else {
            TextPageView(
                style = style,
                paginator = paginator,
                page = state.pageIndex,
                speechCursor = speechCursor,
                highlights = highlightSpans,
                onPageChanged = { page, offset ->
                    vm.onPageRendered(page, offset)
                    onSelection("", -1, -1)
                },
                onLongPress = onLongPress
            )
        }
    }
}

// ------------------------------------------------------------------- chrome

/** Items of the reader's overflow menu (secondary actions live here). */
enum class ReaderMenuAction { SEARCH, SPEECH, BOOKMARKS, NOTES, APPEARANCE }

@Composable
private fun ReaderTopBar(
    title: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onMore: (ReaderMenuAction) -> Unit
) {
    val glass = LocalGlass.current
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = glass.topBarTint.copy(alpha = 0.92f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (chapterTitle.isNotBlank()) {
                    Text(
                        chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            AuroraIconButton(Icons.Default.List, "目录", onToc)
            // Everything that is not "table of contents" lives in the overflow
            // menu, keeping the bar to the two actions readers use constantly.
            Box {
                AuroraIconButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "更多",
                    onClick = { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("搜索") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        onClick = { menuOpen = false; onMore(ReaderMenuAction.SEARCH) }
                    )
                    DropdownMenuItem(
                        text = { Text("朗读") },
                        leadingIcon = { Icon(Icons.Default.VolumeUp, null) },
                        onClick = { menuOpen = false; onMore(ReaderMenuAction.SPEECH) }
                    )
                    DropdownMenuItem(
                        text = { Text("书签") },
                        leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                        onClick = { menuOpen = false; onMore(ReaderMenuAction.BOOKMARKS) }
                    )
                    DropdownMenuItem(
                        text = { Text("笔记") },
                        leadingIcon = { Icon(Icons.Default.Note, null) },
                        onClick = { menuOpen = false; onMore(ReaderMenuAction.NOTES) }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("排版") },
                        leadingIcon = { Icon(Icons.Default.TextFields, null) },
                        onClick = { menuOpen = false; onMore(ReaderMenuAction.APPEARANCE) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    speechState: com.oceanreader.tts.SpeechState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onProgress: (Float) -> Unit,
    onSpeechToggle: () -> Unit,
    onSpeechPanel: () -> Unit
) {
    val glass = LocalGlass.current
    var sliderPosition by remember { mutableFloatStateOf(state.percent) }

    // Re-sync when the book itself moves the progress (e.g. speech follow).
    LaunchedEffect(state.percent) { sliderPosition = state.percent }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = glass.bottomBarTint.copy(alpha = 0.92f),
        tonalElevation = 2.dp
    ) {
        Column {
            // Progress
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.isPaged) {
                        "第 ${state.pageIndex + 1} / ${state.pageCount} 页"
                    } else {
                        "第 ${state.pageIndex + 1} / ${state.flowPages} 页"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = { onProgress(sliderPosition) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    "${(state.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuroraIconButton(Icons.Default.ChevronLeft, "上一页", onPrev)
                AuroraIconButton(Icons.Default.ChevronRight, "下一页", onNext)

                // TTS button
                FilledIconButton(
                    onClick = onSpeechToggle,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (speechState.playing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        if (speechState.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (speechState.playing) "暂停朗读" else "开始朗读"
                    )
                }

                AuroraIconButton(Icons.Default.List, "目录", onSpeechPanel)
            }
        }
    }
}

@Composable
private fun SelectionToolbar(
    text: String,
    onHighlight: () -> Unit,
    onUnderline: () -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text.take(20),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            AuroraIconButton(Icons.Default.FormatColorFill, "高亮", onHighlight)
            AuroraIconButton(Icons.Default.FormatUnderlined, "下划线", onUnderline)
            AuroraIconButton(Icons.Default.Note, "笔记", onNote)
            AuroraIconButton(Icons.Default.ContentCopy, "复制", onCopy)
            AuroraIconButton(Icons.Default.Close, "取消", onClear)
        }
    }
}

// ------------------------------------------------------------------- panels

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPanelSheet(
    panel: ReaderPanel,
    state: ReaderUiState,
    vm: ReaderViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        when (panel) {
            ReaderPanel.TOC -> TocPanel(state = state, vm = vm)
            ReaderPanel.APPEARANCE -> AppearancePanel(vm = vm)
            ReaderPanel.SPEECH -> SpeechPanel(vm = vm)
            ReaderPanel.SEARCH -> SearchPanel(state = state, vm = vm)
            ReaderPanel.BOOKMARK -> BookmarkPanel(state = state, vm = vm)
            ReaderPanel.NOTES -> NotesPanel(state = state, vm = vm)
            ReaderPanel.NONE -> {}
        }
    }
}

@Composable
private fun TocPanel(state: ReaderUiState, vm: ReaderViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .padding(16.dp)
    ) {
        itemsIndexed(state.chapters) { index, chapter ->
            ListItem(
                headlineContent = { Text(chapter.title) },
                modifier = Modifier.clickable { vm.goToChapter(index) },
                colors = ListItemDefaults.colors(
                    containerColor = if (index == state.chapterIndex)
                        MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun AppearancePanel(vm: ReaderViewModel) {
    val prefs by vm.prefs.collectAsState()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palettes = if (dark) PagePalettesDark else PagePalettesLight
    val paletteId = if (dark) prefs.darkPagePalette else prefs.pagePalette

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        SheetHeader(title = "排版", subtitle = "字号、行距、边距与页面底色即时生效")

        // Font family
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("字体", modifier = Modifier.weight(1f))
            Text(
                prefs.fontFamily.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppRowControls(
            options = FontFamilyOption.entries.map { it.label },
            selectedIndex = FontFamilyOption.entries.indexOf(prefs.fontFamily),
            onSelect = { index ->
                vm.setFont { store -> store.setFontFamily(FontFamilyOption.entries[index]) }
            }
        )

        SliderRow(
            label = "字号",
            valueLabel = "${prefs.fontSize}sp",
            value = prefs.fontSize.toFloat(),
            range = 12f..32f,
            steps = 19,
            onChange = { v -> vm.setFont { store -> store.setFontSize(v.toInt()) } }
        )
        SliderRow(
            label = "行距",
            valueLabel = "${prefs.lineSpacing}%",
            value = prefs.lineSpacing,
            range = 100f..300f,
            steps = 19,
            onChange = { v -> vm.setFont { store -> store.setLineSpacing(v.toInt()) } }
        )
        SliderRow(
            label = "页边距",
            valueLabel = "${prefs.horizontalPadding}dp",
            value = prefs.horizontalPadding.toFloat(),
            range = 8f..64f,
            onChange = { v -> vm.setFont { store -> store.setMarginH(v.toInt()) } }
        )
        SliderRow(
            label = "段间距",
            valueLabel = "${prefs.paragraphSpacing}dp",
            value = prefs.paragraphSpacing.toFloat(),
            range = 0f..40f,
            onChange = { v -> vm.setFont { store -> store.setParaSpacing(v.toInt()) } }
        )

        // First-line indent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("首行缩进", modifier = Modifier.weight(1f))
            Text(
                "${prefs.firstLineIndent} 字符",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppRowControls(
            options = listOf("0", "1", "2", "3", "4"),
            selectedIndex = prefs.firstLineIndent.coerceIn(0, 4),
            onSelect = { index -> vm.setFont { store -> store.setIndent(index) } }
        )

        // Bold / justify
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("加粗", modifier = Modifier.weight(1f))
            Switch(
                checked = prefs.boldText,
                onCheckedChange = { v -> vm.setFont { store -> store.setBoldText(v) } }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("两端对齐", modifier = Modifier.weight(1f))
            Switch(
                checked = prefs.justifyText,
                onCheckedChange = { v -> vm.setFont { store -> store.setJustifyText(v) } }
            )
        }

        // Page palette
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("页面底色", modifier = Modifier.weight(1f))
            Text(
                palettes.firstOrNull { it.id == paletteId }?.name ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            palettes.forEach { palette ->
                val selected = palette.id == paletteId
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(palette.background)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable {
                            vm.setFont { store ->
                                if (dark) store.setDarkPagePalette(palette.id) else store.setPagePalette(palette.id)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = palette.text,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Segmented control used across the appearance panel. */
@Composable
private fun AppRowControls(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screen)
    ) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/** Label + value + slider row for the appearance panel. */
@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun SpeechPanel(vm: ReaderViewModel) {
    val speechState by vm.speech.state.collectAsState()
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetHeader(title = "朗读", subtitle = "借助系统 TTS 引擎跟读")

        SliderRow(
            label = "语速",
            valueLabel = String.format("%.1fx", speechState.speed),
            value = speechState.speed,
            range = 0.3f..3f,
            onChange = { vm.speech.setSpeed(it) }
        )
        SliderRow(
            label = "音调",
            valueLabel = String.format("%.1fx", speechState.pitch),
            value = speechState.pitch,
            range = 0.5f..2f,
            onChange = { vm.speech.setPitch(it) }
        )

        // Sleep timer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("定时停止", modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.speech.setSleepTimer(15) }) { Text("15分钟") }
            Spacer(Modifier.width(AppSpacing.xs))
            TextButton(onClick = { vm.speech.setSleepTimer(30) }) { Text("30分钟") }
            Spacer(Modifier.width(AppSpacing.xs))
            TextButton(onClick = { vm.speech.setSleepTimer(0) }) { Text("关闭") }
        }
        Spacer(Modifier.height(AppSpacing.lg))
    }
}

@Composable
private fun SearchPanel(state: ReaderUiState, vm: ReaderViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetHeader(title = "搜索", subtitle = "在本书全文中查找")
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { vm.search(it) },
            placeholder = { Text("输入关键词") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen)
        )
        Text(
            "${state.searchHits.size} 个结果",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm)
        )
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(state.searchHits) { index, hit ->
                ListItem(
                    headlineContent = { Text(state.chapters.getOrNull(hit.first)?.title.orEmpty()) },
                    supportingContent = { Text("第 ${hit.first + 1} 章") },
                    modifier = Modifier.clickable { vm.jumpToSearchHit(index) }
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.lg))
    }
}

@Composable
private fun BookmarkPanel(state: ReaderUiState, vm: ReaderViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetHeader(title = "书签", subtitle = "在章节或页面位置插入书签")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("当前位置", modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.toggleBookmark() }) {
                Text(if (vm.isBookmarked()) "删除书签" else "添加书签")
            }
        }
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(state.bookmarks) { bookmark ->
                ListItem(
                    headlineContent = { Text(bookmark.title.ifBlank { "第 ${bookmark.chapterIndex + 1} 章" }) },
                    supportingContent = { Text(bookmark.excerpt) },
                    modifier = Modifier.clickable {
                        vm.goToChapter(bookmark.chapterIndex, bookmark.offset)
                    },
                    trailingContent = {
                        IconButton(onClick = { vm.deleteBookmark(bookmark.id) }) {
                            Icon(Icons.Default.Delete, "删除")
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.lg))
    }
}

@Composable
private fun NotesPanel(state: ReaderUiState, vm: ReaderViewModel) {
    val context = LocalContext.current
    val repo = remember(context) { com.oceanreader.AppContainer.from(context).repository }
    val notes by repo.notes(state.book?.id.orEmpty()).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        SheetHeader(title = "笔记", subtitle = "选中正文后即可写下想法")

        if (state.selection.isNotBlank()) {
            Text(
                text = state.selection.take(80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.screen)
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("写下你的想法") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.sm)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screen),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { draft = ""; vm.clearSelection() }) { Text("取消") }
                Spacer(Modifier.width(AppSpacing.sm))
                Button(
                    enabled = draft.isNotBlank() && state.book != null,
                    onClick = {
                        val book = state.book ?: return@Button
                        val noteBook = book
                        val content = draft.trim()
                        val quote = state.selection
                        val chapter = state.chapterIndex
                        val offset = state.selectionStart
                        scope.launch {
                            repo.addNote(noteBook, chapter, offset, content, quote)
                        }
                        draft = ""
                        vm.clearSelection()
                    }
                ) { Text("保存") }
            }
        }

        if (notes.isNotEmpty()) {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(notes) { note ->
                    ListItem(
                        headlineContent = { Text(note.content) },
                        supportingContent = { Text(note.quote) }
                    )
                }
            }
        } else if (state.selection.isBlank()) {
            Text(
                "还没有笔记。在正文中长按选中句子，再点「笔记」即可创建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.screen)
            )
        }
        Spacer(Modifier.height(AppSpacing.lg))
    }
}
