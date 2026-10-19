package com.oceanreader.ui.shelf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.oceanreader.data.db.BookEntity
import com.oceanreader.ui.components.EmptyState
import androidx.compose.foundation.ExperimentalFoundationApi
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    onOpenBook: (String) -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit = {},
    onImportFiles: () -> Unit = {},
    onImportFolder: () -> Unit = {},
    vm: ShelfViewModel = viewModel()
) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    var showImportSheet by remember { mutableStateOf(false) }

    // The activity publishes picks here. Listeners must unregister, or every
    // shelf composition that ever lived would also react to the next pick.
    DisposableEffect(vm) {
        val fileListener: (List<android.net.Uri>) -> Unit = { uris -> vm.importFiles(uris) }
        val folderListener: (android.net.Uri) -> Unit = { uri -> vm.importFolder(uri) }
        com.oceanreader.ImportBus.onFiles(fileListener)
        com.oceanreader.ImportBus.onFolder(folderListener)
        onDispose {
            com.oceanreader.ImportBus.releaseFiles(fileListener)
            com.oceanreader.ImportBus.releaseFolder(folderListener)
        }
    }

    // One snackbar per finished batch, then the message is consumed.
    LaunchedEffect(state.importResultMessage) {
        val message = state.importResultMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        vm.consumeImportResult()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("我的书架", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = onStats) {
                        Icon(Icons.Default.BarChart, contentDescription = "阅读统计")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "导入书籍")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Batch import progress
            if (state.importing) {
                ImportProgressBanner(
                    completed = state.importCompleted,
                    total = state.importTotal,
                    fileName = state.importFileName,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = vm::setSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索书名 / 作者") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp)
            )

            if (state.continueReading != null) {
                ContinueReadingCard(
                    book = state.continueReading!!,
                    onClick = { onOpenBook(state.continueReading!!.id) },
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (state.selection.isNotEmpty()) {
                SelectionBar(
                    count = state.selection.size,
                    onPin = {
                        val pinned = state.books.firstOrNull { it.id in state.selection }?.pinned ?: false
                        vm.pinSelected(!pinned)
                    },
                    onDelete = vm::deleteSelected,
                    onClear = vm::clearSelection
                )
            }

            if (state.books.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.LibraryBooks,
                    title = if (state.searchQuery.isBlank()) "书架还是空的" else "没有找到相关书籍",
                    hint = if (state.searchQuery.isBlank()) "点击右下角按钮导入第一本书" else "换个关键词试试",
                    modifier = Modifier.weight(1f)
                )
            } else {
                val columns = if (state.gridColumns > 0) state.gridColumns else 3
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.books, key = { it.id }) { book ->
                        BookGridItem(
                            book = book,
                            selected = book.id in state.selection,
                            showCover = state.gridCover,
                            onClick = {
                                if (state.selection.isNotEmpty()) vm.toggleSelection(book.id)
                                else onOpenBook(book.id)
                            },
                            onLongClick = { vm.toggleSelection(book.id) }
                        )
                    }
                }
            }
        }

        // Import method chooser: files (multi-select) or a whole folder.
        if (showImportSheet) {
            ImportOptionsSheet(
                onFiles = {
                    showImportSheet = false
                    onImportFiles()
                },
                onFolder = {
                    showImportSheet = false
                    onImportFolder()
                },
                onDismiss = { showImportSheet = false }
            )
        }
    }
}

/** Live progress of a batch import, pinned under the top bar. */
@Composable
private fun ImportProgressBanner(
    completed: Int,
    total: Int,
    fileName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = if (total > 0) "正在导入 ${completed + 1} / $total" else "正在导入…",
                style = MaterialTheme.typography.titleSmall
            )
            if (fileName.isNotBlank()) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val fraction = if (total > 0) ((completed + 1f) / total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

/** Bottom sheet offering the two ways to add books to the shelf. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportOptionsSheet(
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "导入书籍",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
            )
            ImportOptionRow(
                icon = Icons.Default.InsertDriveFile,
                title = "选择文件",
                subtitle = "一次可多选：TXT / EPUB / PDF / MOBI / Word / MD / RTF / HTML / FB2",
                onClick = onFiles
            )
            ImportOptionRow(
                icon = Icons.Default.FolderOpen,
                title = "选择文件夹",
                subtitle = "自动扫描文件夹内所有可读的书籍（含子文件夹）",
                onClick = onFolder
            )
        }
    }
}

@Composable
private fun ImportOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ContinueReadingCard(
    book: BookEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                coverPath = book.coverPath,
                title = book.title,
                modifier = Modifier.size(96.dp, 84.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = book.percent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Text(
                    "${(book.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$count 本已选", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onPin) { Icon(Icons.Default.PushPin, "置顶") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, "取消") }
        }
    }
}

@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (coverPath != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(coverPath))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    book: BookEntity,
    selected: Boolean,
    showCover: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            if (showCover) {
                BookCover(
                    coverPath = book.coverPath,
                    title = book.title,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            book.author,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}