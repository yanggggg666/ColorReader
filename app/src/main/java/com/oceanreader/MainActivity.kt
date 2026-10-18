package com.oceanreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.oceanreader.data.ImportResult
import com.oceanreader.ui.reader.ReaderScreen
import com.oceanreader.ui.settings.SettingsScreen
import com.oceanreader.ui.shelf.ShelfScreen
import com.oceanreader.ui.stats.StatsScreen
import com.oceanreader.ui.theme.ColorReaderTheme

class MainActivity : ComponentActivity() {

    private var pendingImport: ((Uri) -> Unit)? = null

    companion object {
        const val EXTRA_OPEN_READER = "open_reader"
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            pendingImport?.invoke(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = AppContainer(this)

        setContent {
            val darkMode by container.settings.themeMode.collectAsState()
            val accent by container.settings.accentIndex.collectAsState()
            val dynamic by container.settings.dynamicColor.collectAsState()

            ColorReaderTheme(
                darkTheme = darkMode == com.oceanreader.data.DarkMode.DARK ||
                    (darkMode == com.oceanreader.data.DarkMode.SYSTEM &&
                        androidx.compose.foundation.isSystemInDarkTheme()),
                accentIndex = accent,
                useDynamicColor = dynamic
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        onImport = { mimeTypes ->
                            pendingImport = null
                            importLauncher.launch(mimeTypes)
                        }
                    )
                }
            }
        }
    }

    fun requestImport(onPicked: (Uri) -> Unit) {
        pendingImport = onPicked
        importLauncher.launch(arrayOf("*/*"))
    }
}

@Composable
private fun AppNavHost(onImport: (Array<String>) -> Unit) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "shelf") {
        composable("shelf") {
            ShelfScreen(
                onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                onSettings = { navController.navigate("settings") },
                onStats = { navController.navigate("stats") },
                onImport = { onImport(arrayOf("*/*")) }
            )
        }
        composable(
            "reader/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("stats") {
            StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}