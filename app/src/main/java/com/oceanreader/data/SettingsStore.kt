package com.oceanreader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oceanreader.data.model.Brightness
import com.oceanreader.data.model.DarkMode
import com.oceanreader.data.model.FontFamilyOption
import com.oceanreader.data.model.PageTurnMode
import com.oceanreader.data.model.ShelfSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

/** Resolved snapshot of every preference the UI reads. */
data class Settings(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val accentIndex: Int = 0,
    val dynamicColor: Boolean = true,
    val pagePalette: String = "white",
    val darkPagePalette: String = "dark",
    val fontSize: Int = 18,
    val lineSpacing: Float = 1.6f,
    val horizontalPadding: Int = 22,
    val verticalPadding: Int = 20,
    val paragraphSpacing: Int = 10,
    val fontFamily: FontFamilyOption = FontFamilyOption.SERIF,
    val boldText: Boolean = false,
    val justifyText: Boolean = false,
    val firstLineIndent: Int = 2,
    val pageTurn: PageTurnMode = PageTurnMode.SLIDE,
    val keepScreenOn: Boolean = true,
    val fullscreen: Boolean = false,
    val volumeKeyPaging: Boolean = true,
    val brightness: Brightness = Brightness.AUTO,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVoice: String = "",
    val ttsHighlight: Boolean = true,
    val ttsAutoNextChapter: Boolean = true,
    val copyOnImport: Boolean = true,
    val shelfSort: ShelfSort = ShelfSort.RECENT,
    val showCoverGrid: Boolean = true,
    val gridColumns: Int = 0,
    val immersiveHint: Boolean = true
)

/** Global, app-wide preferences. Per-book overrides live in Room. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val FONT = stringPreferencesKey("font_family")
        val FONT_SIZE = intPreferencesKey("font_size")
        val BOLD = booleanPreferencesKey("bold_text")
        val JUSTIFY = booleanPreferencesKey("justify_text")
        val LINE_SPACING = intPreferencesKey("line_spacing")
        val PARA_SPACING = intPreferencesKey("para_spacing")
        val MARGIN_H = intPreferencesKey("margin_h")
        val MARGIN_V = intPreferencesKey("margin_v")
        val INDENT = intPreferencesKey("indent")
        val PAGE_PALETTE = stringPreferencesKey("page_palette")
        val DARK_PAGE_PALETTE = stringPreferencesKey("page_palette_dark")
        val TURN_MODE = stringPreferencesKey("turn_mode")
        val FULLSCREEN = booleanPreferencesKey("fullscreen")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEY_PAGING = booleanPreferencesKey("volume_key_paging")
        val BRIGHTNESS = stringPreferencesKey("brightness")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = intPreferencesKey("accent_index")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_HIGHLIGHT = booleanPreferencesKey("tts_highlight")
        val TTS_AUTO_NEXT = booleanPreferencesKey("tts_auto_next")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val SHOW_COVER_GRID = booleanPreferencesKey("show_cover_grid")
    }

    /** Emits the full resolved preference snapshot. */
    val prefs: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            darkMode = DarkMode.valueOf(p[Keys.THEME_MODE] ?: DarkMode.SYSTEM.name),
            accentIndex = p[Keys.ACCENT] ?: 0,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            pagePalette = p[Keys.PAGE_PALETTE] ?: "white",
            darkPagePalette = p[Keys.DARK_PAGE_PALETTE] ?: "dark",
            fontSize = p[Keys.FONT_SIZE] ?: 18,
            lineSpacing = (p[Keys.LINE_SPACING] ?: 170) / 100f,
            horizontalPadding = p[Keys.MARGIN_H] ?: 22,
            verticalPadding = p[Keys.MARGIN_V] ?: 20,
            paragraphSpacing = p[Keys.PARA_SPACING] ?: 10,
            fontFamily = FontFamilyOption.from(p[Keys.FONT]),
            boldText = p[Keys.BOLD] ?: false,
            justifyText = p[Keys.JUSTIFY] ?: false,
            firstLineIndent = p[Keys.INDENT] ?: 2,
            pageTurn = runCatching { PageTurnMode.valueOf(p[Keys.TURN_MODE] ?: "SLIDE") }
                .getOrDefault(PageTurnMode.SLIDE),
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            fullscreen = p[Keys.FULLSCREEN] ?: false,
            volumeKeyPaging = p[Keys.VOLUME_KEY_PAGING] ?: true,
            brightness = runCatching { Brightness.valueOf(p[Keys.BRIGHTNESS] ?: "AUTO") }
                .getOrDefault(Brightness.AUTO),
            ttsSpeed = p[Keys.TTS_SPEED] ?: 1.0f,
            ttsPitch = p[Keys.TTS_PITCH] ?: 1.0f,
            ttsVoice = p[Keys.TTS_VOICE] ?: "",
            ttsHighlight = p[Keys.TTS_HIGHLIGHT] ?: true,
            ttsAutoNextChapter = p[Keys.TTS_AUTO_NEXT] ?: true,
            copyOnImport = true,
            shelfSort = runCatching { ShelfSort.valueOf(p[Keys.SORT_MODE] ?: "RECENT") }
                .getOrDefault(ShelfSort.RECENT),
            showCoverGrid = p[Keys.SHOW_COVER_GRID] ?: true,
            gridColumns = p[Keys.GRID_COLUMNS] ?: 0,
            immersiveHint = true
        )
    }

    suspend fun setThemeMode(v: DarkMode) = put(Keys.THEME_MODE, v.name)
    suspend fun setAccent(v: Int) = put(Keys.ACCENT, v)
    suspend fun setDynamicColor(v: Boolean) = put(Keys.DYNAMIC_COLOR, v)
    suspend fun setPagePalette(v: String) = put(Keys.PAGE_PALETTE, v)
    suspend fun setDarkPagePalette(v: String) = put(Keys.DARK_PAGE_PALETTE, v)
    suspend fun setFontSize(v: Int) = put(Keys.FONT_SIZE, v.coerceIn(12, 32))
    suspend fun setBoldText(v: Boolean) = put(Keys.BOLD, v)
    suspend fun setJustifyText(v: Boolean) = put(Keys.JUSTIFY, v)
    suspend fun setLineSpacing(v: Int) = put(Keys.LINE_SPACING, v.coerceIn(100, 300))
    suspend fun setParaSpacing(v: Int) = put(Keys.PARA_SPACING, v.coerceIn(0, 40))
    suspend fun setMarginH(v: Int) = put(Keys.MARGIN_H, v.coerceIn(0, 80))
    suspend fun setMarginV(v: Int) = put(Keys.MARGIN_V, v.coerceIn(0, 80))
    suspend fun setIndent(v: Int) = put(Keys.INDENT, v.coerceIn(0, 4))
    suspend fun setTurnMode(v: PageTurnMode) = put(Keys.TURN_MODE, v.name)
    suspend fun setFullscreen(v: Boolean) = put(Keys.FULLSCREEN, v)
    suspend fun setKeepScreenOn(v: Boolean) = put(Keys.KEEP_SCREEN_ON, v)
    suspend fun setVolumeKeyPaging(v: Boolean) = put(Keys.VOLUME_KEY_PAGING, v)
    suspend fun setBrightness(v: Brightness) = put(Keys.BRIGHTNESS, v.name)
    suspend fun setFontFamily(v: FontFamilyOption) = put(Keys.FONT, v.key)
    suspend fun setTtsSpeed(v: Float) = put(Keys.TTS_SPEED, v.coerceIn(0.3f, 3f))
    suspend fun setTtsPitch(v: Float) = put(Keys.TTS_PITCH, v.coerceIn(0.5f, 2f))
    suspend fun setTtsVoice(v: String?) = put(Keys.TTS_VOICE, v.orEmpty())
    suspend fun setTtsHighlight(v: Boolean) = put(Keys.TTS_HIGHLIGHT, v)
    suspend fun setTtsAutoNext(v: Boolean) = put(Keys.TTS_AUTO_NEXT, v)
    suspend fun setShelfSort(v: ShelfSort) = put(Keys.SORT_MODE, v.name)
    suspend fun setGridColumns(v: Int) = put(Keys.GRID_COLUMNS, v.coerceIn(0, 5))
    suspend fun setShowCoverGrid(v: Boolean) = put(Keys.SHOW_COVER_GRID, v)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}