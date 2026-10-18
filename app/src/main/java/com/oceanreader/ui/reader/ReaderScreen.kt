package com.oceanreader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oceanreader.data.BookEntity
import com.oceanreader.reader.ReaderPanel
import com.oceanreader.reader.ReaderUiState
import com.oceanreader.reader.ReaderViewModel
import com.oceanreader.tts.SpeechController
import com.oceanreader.ui.components.AuroraIconButton
import com.oceanreader.ui.theme.LocalAccent
import com.oceanreader.ui.theme.LocalGlass
import com.oceanreader.ui.theme.SuperCard
import com.oceanreader.ui.theme.SuperSheet
import com.oceanreader.ui.theme.glassPanel
import com.oceanreader.ui.theme.softShadow
import com.oceanreader.ui.theme.tappable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    vm: ReaderViewModel = viewModel(
        factory = ReaderViewModelFactory(bookId)
    )
) {
    val state by vm.ui.collectAsState()
    val speechState by vm.speech.state.collectAsState()
    val speechCursor by vm.speech.cursor.collectAsState()
    val accent = LocalAccent.current
    val glass = LocalGlass.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Reading surface
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.error != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error ?: "无法打开", color = MaterialTheme.colorScheme.error)
            }
        } else {
            ReaderSurface(
                state = state,
                speechCursor = speechCursor,
                onTap = { vm.toggleChrome() },
                onPageChanged = { page, offset -> vm.onPageRendered(page, offset) },
                onSelection = { text, start, end -> vm.onSelectionChanged(text, start, end) },
                onLongPress = { offset -> vm.onSelectionChanged("", offset, offset) }
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
                onSearch = { vm.setPanel(ReaderPanel.SEARCH) },
                onAppearance = { vm.setPanel(ReaderPanel.APPEARANCE) },
                onSpeech = { vm.setPanel(ReaderPanel.SPEECH) },
                onBookmarks = { vm.setPanel(ReaderPanel.BOOKMARK) },
                onNotes = { vm.setPanel(ReaderPanel.NOTES) }
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
                onProgress = { page -> vm.goToPage(page) },
                onSpeechToggle = {
                    if (speechState.playing) vm.stopSpeaking() else vm.startSpeaking()
                },
                onSpeechPanel = { vm.setPanel(ReaderPanel.SPEECH) }
            )
        }

        // Selection toolbar
        if (state.selection.isNotBlank()) {
            SelectionToolbar(
                text = state.selection,
                onHighlight = { vm.highlightSelection() },
                onUnderline = { vm.highlightSelection(underline = true) },
                onNote = { vm.setPanel(ReaderPanel.NOTES) },
                onCopy = { /* clipboard */ },
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

@Composable
private fun ReaderSurface(
    state: ReaderUiState,
    speechCursor: com.oceanreader.tts.SpeechCursor,
    onTap: () -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    onSelection: (String, Int, Int) -> Unit,
    onLongPress: (Int) -> Unit
) {
    val context = LocalContext.current
    val paginator = remember { com.oceanreader.reader.TextPaginator() }
    val paint = remember {
        android.text.TextPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
        }
    }

    // Paginate whenever the chapter or style changes.
    LaunchedEffect(state.chapterText, state.chapterIndex) {
        paginator.paginate(
            text = state.chapterText,
            paint = paint,
            width = context.resources.displayMetrics.widthPixels,
            height = context.resources.displayMetrics.heightPixels,
            spacingMultiplier = 1.6f,
            spacingAdd = 0
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onTap)
    ) {
        if (state.isPaged) {
            PdfPageView(
                page = state.pageIndex,
                pageCount = state.pageCount,
                onPageChanged = { onPageChanged(it, 0) }
            )
        } else {
            TextPageView(
                paginator = paginator,
                page = state.pageIndex,
                speechCursor = speechCursor,
                onPageChanged = onPageChanged,
                onSelection = onSelection,
                onLongPress = onLongPress
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onSearch: () -> Unit,
    onAppearance: () -> Unit,
    onSpeech: () -> Unit,
    onBookmarks: () -> Unit,
    onNotes: () -> Unit
) {
    val glass = LocalGlass.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = glass.topBarTint.copy(alpha = 0.92f),
        tonalElevation = 2.dp
    ) {
        Column {
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
                AuroraIconButton(Icons.Default.Search, "搜索", onSearch)
                AuroraIconButton(Icons.Default.TextFields, "排版", onAppearance)
                AuroraIconButton(Icons.Default.VolumeUp, "朗读", onSpeech)
                AuroraIconButton(Icons.Default.Bookmark, "书签", onBookmarks)
                AuroraIconButton(Icons.Default.Note, "笔记", onNotes)
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
    onProgress: (Int) -> Unit,
    onSpeechToggle: () -> Unit,
    onSpeechPanel: () -> Unit
) {
    val glass = LocalGlass.current
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
                    "${(state.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = state.percent,
                    onValueChange = { onProgress((it * 100).toInt()) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
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
            .padding(16.dp),
        shape = SuperCard,
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
        shape = SuperSheet,
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
    Column(modifier = Modifier.padding(16.dp)) {
        Text("排版设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        // Font size
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", modifier = Modifier.weight(1f))
            Slider(
                value = 18f,
                onValueChange = { },
                valueRange = 12f..32f,
                modifier = Modifier.weight(2f)
            )
        }
        // Line spacing
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("行距", modifier = Modifier.weight(1f))
            Slider(
                value = 1.6f,
                onValueChange = { },
                valueRange = 1.0f..2.5f,
                modifier = Modifier.weight(2f)
            )
        }
    }
}

@Composable
private fun SpeechPanel(vm: ReaderViewModel) {
    val speechState by vm.speech.state.collectAsState()
    Column(modifier = Modifier.padding(16.dp)) {
        Text("朗读设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        // Speed
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("语速", modifier = Modifier.weight(1f))
            Slider(
                value = speechState.speed,
                onValueChange = { vm.speech.setSpeed(it) },
                valueRange = 0.3f..3f,
                modifier = Modifier.weight(2f)
            )
        }

        // Pitch
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音调", modifier = Modifier.weight(1f))
            Slider(
                value = speechState.pitch,
                onValueChange = { vm.speech.setPitch(it) },
                valueRange = 0.5f..2f,
                modifier = Modifier.weight(2f)
            )
        }

        // Sleep timer
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("定时停止", modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.speech.setSleepTimer(15) }) { Text("15分钟") }
            TextButton(onClick = { vm.speech.setSleepTimer(30) }) { Text("30分钟") }
            TextButton(onClick = { vm.speech.setSleepTimer(0) }) { Text("关闭") }
        }
    }
}

@Composable
private fun SearchPanel(state: ReaderUiState, vm: ReaderViewModel) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { vm.search(it) },
            placeholder = { Text("搜索正文") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("${state.searchHits.size} 个结果", style = MaterialTheme.typography.bodySmall)
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(state.searchHits) { index, hit ->
                ListItem(
                    headlineContent = { Text("第 ${hit.first + 1} 章") },
                    supportingContent = { Text(state.chapters.getOrNull(hit.first)?.title.orEmpty()) },
                    modifier = Modifier.clickable { vm.jumpToSearchHit(index) }
                )
            }
        }
    }
}

@Composable
private fun BookmarkPanel(state: ReaderUiState, vm: ReaderViewModel) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("书签", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
    }
}

@Composable
private fun NotesPanel(state: ReaderUiState, vm: ReaderViewModel) {
    val notes by com.oceanreader.AppContainer.from(LocalContext.current)
        .repository.notes(state.book?.id.orEmpty()).collectAsState(initial = emptyList())
    Column(modifier = Modifier.padding(16.dp)) {
        Text("笔记", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (state.selection.isNotBlank()) {
            OutlinedTextField(
                value = "",
                onValueChange = { },
                placeholder = { Text("为选中文字添加笔记") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(notes) { note ->
                ListItem(
                    headlineContent = { Text(note.content) },
                    supportingContent = { Text(note.quote) }
                )
            }
        }
    }
}