package com.oceanreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oceanreader.data.model.DarkMode
import com.oceanreader.ui.reader.ReaderScreen
import com.oceanreader.ui.settings.SettingsScreen
import com.oceanreader.ui.shelf.ShelfScreen
import com.oceanreader.ui.stats.StatsScreen
import com.oceanreader.ui.theme.ColorReaderTheme

/**
 * Single-activity shell.
 *
 * Only an Activity can start the system document pickers, so the launchers
 * are created here and handed down as plain lambdas. Picked files (single,
 * multi-select or a whole folder tree) are published to [ImportBus] so the
 * ViewModels stay free of framework types.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "Share to" / "open with" may arrive before the shelf is composed;
        // ImportBus buffers the pick until a listener registers.
        handleDocumentIntent(intent)
        val container = AppContainer.from(applicationContext)

        setContent {
            val prefs by container.settings.prefs.collectAsState(initial = com.oceanreader.data.Settings())
            val darkMode = prefs.darkMode
            val accent = prefs.accentIndex
            val dynamic = prefs.dynamicColor

            ColorReaderTheme(
                darkTheme = darkMode == DarkMode.DARK ||
                    (darkMode == DarkMode.SYSTEM && isSystemInDarkTheme()),
                accentIndex = accent,
                useDynamicColor = dynamic
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDocumentIntent(intent)
    }

    /** Turns a share/view intent into a batch import. */
    private fun handleDocumentIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                ImportBus.publishFiles(listOf(uri))
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.filterNotNull()
                    ?: return
                uris.forEach { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                ImportBus.publishFiles(uris)
            }

            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                ImportBus.publishFiles(listOf(uri))
            }
        }
    }
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    fun keepReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    // Single or multi-select: the picker returns every chosen document.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { keepReadPermission(it) }
            ImportBus.publishFiles(uris)
        }
    }

    // Folder pick: returns a tree URI; the importer walks it for documents.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            keepReadPermission(uri)
            ImportBus.publishFolder(uri)
        }
    }

    NavHost(navController = navController, startDestination = "shelf") {
        composable("shelf") {
            ShelfScreen(
                onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                onSettings = { navController.navigate("settings") },
                onStats = { navController.navigate("stats") },
                onImportFiles = { picker.launch(arrayOf("*/*")) },
                onImportFolder = { folderPicker.launch(null) }
            )
        }
        composable(
            "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { entry ->
            ReaderScreen(
                bookId = entry.arguments?.getString("bookId").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                settings = AppContainer.from(context).settings
            )
        }
        composable("stats") {
            StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Broadcasts picked documents to the most recent subscriber.
 *
 * Two channels because the shell hands the shelf either concrete files or a
 * folder tree to walk. Picks that arrive before the shelf is composed (a cold
 * start from "share to" or "open with") are buffered and delivered as soon as
 * a listener registers. Listeners must [release] themselves when their screen
 * leaves composition, otherwise every past composable would also react.
 */
object ImportBus {

    private val fileListeners = ArrayDeque<(List<Uri>) -> Unit>()
    private val folderListeners = ArrayDeque<(Uri) -> Unit>()
    private var bufferedFiles: List<Uri>? = null
    private var bufferedFolder: Uri? = null

    fun onFiles(listener: (List<Uri>) -> Unit) {
        fileListeners.addLast(listener)
        bufferedFiles?.let { uris ->
            bufferedFiles = null
            listener.invoke(uris)
        }
    }

    fun onFolder(listener: (Uri) -> Unit) {
        folderListeners.addLast(listener)
        bufferedFolder?.let { uri ->
            bufferedFolder = null
            listener.invoke(uri)
        }
    }

    fun releaseFiles(listener: (List<Uri>) -> Unit) {
        fileListeners.remove(listener)
    }

    fun releaseFolder(listener: (Uri) -> Unit) {
        folderListeners.remove(listener)
    }

    fun publishFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (fileListeners.isEmpty()) {
            bufferedFiles = uris
            return
        }
        val pending = ArrayList(fileListeners)
        fileListeners.clear()
        pending.forEach { it.invoke(uris) }
    }

    fun publishFolder(treeUri: Uri) {
        if (folderListeners.isEmpty()) {
            bufferedFolder = treeUri
            return
        }
        val pending = ArrayList(folderListeners)
        folderListeners.clear()
        pending.forEach { it.invoke(treeUri) }
    }
}
