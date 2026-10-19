package com.oceanreader

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
 * Only an Activity can start the system document picker, so the launcher is
 * created here and handed down as a plain lambda. Picked files are published
 * to [ImportBus] so the ViewModels stay free of framework types.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            ImportBus.publish(uri)
        }
    }

    NavHost(navController = navController, startDestination = "shelf") {
        composable("shelf") {
            ShelfScreen(
                onOpenBook = { bookId -> navController.navigate("reader/$bookId") },
                onSettings = { navController.navigate("settings") },
                onStats = { navController.navigate("stats") },
                onImportRequest = { picker.launch(arrayOf("*/*")) }
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

/** Broadcasts a picked Uri to the most recent subscriber. */
object ImportBus {
    private val listeners = ArrayDeque<(Uri) -> Unit>()

    fun request(onPicked: (Uri) -> Unit) { listeners.addLast(onPicked) }

    fun publish(uri: Uri) {
        val pending = ArrayList(listeners)
        listeners.clear()
        pending.forEach { it.invoke(uri) }
    }
}