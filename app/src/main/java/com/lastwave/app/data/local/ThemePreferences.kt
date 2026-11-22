package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AccentMode(val storageValue: String) {
    MANUAL("manual"),
    DYNAMIC("dynamic"),
    MONOCHROME("monochrome");

    companion object {
        fun fromStorage(value: String?): AccentMode =
            entries.firstOrNull { it.storageValue == value } ?: MANUAL
    }
}

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/** Surfaces that can opt out of Liquid Glass individually. */
enum class GlassSurface(val id: String) {
    NAVIGATION("navigation"),
    MINI_PLAYER("mini_player"),
    PLAYER_CONTROLS("player_controls"),
    FLOATING("floating"),
    HEADER("header");

    companion object {
        fun fromId(id: String): GlassSurface? = entries.firstOrNull { it.id == id }
        val allIds: Set<String> = entries.map { it.id }.toSet()
    }
}

data class ThemePrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: String = "#E03030",
    val accentLight: String = "#FF6060",
    /** Optional custom four-colour palette, written by the Custom picker.
     *  Null keeps the generated Material palette derived from [accentColor]. */
    val accentContainer: String? = null,
    val accentHighlight: String? = null,
    val accentMode: AccentMode = AccentMode.MANUAL,
    val amoled: Boolean = false,
    /** iOS-style liquid-glass materials. Enabled by default. */
    val liquidGlass: Boolean = true,
    /** Which surfaces may use glass while [liquidGlass] is on. Empty = none. */
    val glassSurfaces: Set<String> = GlassSurface.allIds,
    /** Optional compact Material 3 floating dock instead of the standard bottom tabs. */
    val md3NavBar: Boolean = false,
    /** Whether bottom navigation tabs are completely hidden. */
    val hideNavTabs: Boolean = false,
    /** Haptic feedback on taps, buttons, toggles and gestures across the app. */
    val uiHaptics: Boolean = true,
    /** Dedicated LastWave Signature Theme inspired by the app logo icon (#C9FB00 & #012226). */
    val lastWaveTheme: Boolean = false,
    val useLegacyColors: Boolean = false,
    val appIcon: AppIcon = AppIcon.DEFAULT,
)

@Singleton
class ThemePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("lw_themeMode")
        val ACCENT_COLOR = stringPreferencesKey("lw_accent")
        val ACCENT_LIGHT = stringPreferencesKey("lw_accentLight")
        val ACCENT_CONTAINER = stringPreferencesKey("lw_accentContainer")
        val ACCENT_HIGHLIGHT = stringPreferencesKey("lw_accentHighlight")
        val ACCENT_MODE = stringPreferencesKey("lw_accentMode")
        val AMOLED = booleanPreferencesKey("lw_amoled")
        val LIQUID_GLASS = booleanPreferencesKey("lw_liquidGlass")
        val GLASS_SURFACES = stringPreferencesKey("lw_glass_surfaces")
        val MD3_NAV_BAR = booleanPreferencesKey("lw_md3NavBar")
        val HIDE_NAV_TABS = booleanPreferencesKey("lw_hideNavTabs")
        val UI_HAPTICS = booleanPreferencesKey("lw_ui_haptics")
        val LASTWAVE_THEME = booleanPreferencesKey("lw_lastwave_theme")
        val USE_LEGACY_COLORS = booleanPreferencesKey("lw_useLegacyColors")
        val APP_ICON = stringPreferencesKey("lw_app_icon")
    }

    val prefs: Flow<ThemePrefs> = dataStore.data
        .recoverPreferences("ThemePreferences")
        .map { p ->
            ThemePrefs(
                themeMode = ThemeMode.fromStorage(p.readSafely(Keys.THEME_MODE)),
                accentColor = p.readSafely(Keys.ACCENT_COLOR) ?: "#E03030",
                accentLight = p.readSafely(Keys.ACCENT_LIGHT) ?: "#FF6060",
                accentContainer = p.readSafely(Keys.ACCENT_CONTAINER),
                accentHighlight = p.readSafely(Keys.ACCENT_HIGHLIGHT),
                accentMode = AccentMode.fromStorage(p.readSafely(Keys.ACCENT_MODE)),
                amoled = p.readSafely(Keys.AMOLED) ?: false,
                liquidGlass = p.readSafely(Keys.LIQUID_GLASS) ?: true,
                glassSurfaces = p.readSafely(Keys.GLASS_SURFACES)
                    ?.split(",")?.filter { it.isNotBlank() }?.toSet()
                    ?: GlassSurface.allIds,
                md3NavBar = p.readSafely(Keys.MD3_NAV_BAR) ?: false,
                hideNavTabs = p.readSafely(Keys.HIDE_NAV_TABS) ?: false,
                uiHaptics = p.readSafely(Keys.UI_HAPTICS) ?: true,
                lastWaveTheme = p.readSafely(Keys.LASTWAVE_THEME) ?: false,
                useLegacyColors = p.readSafely(Keys.USE_LEGACY_COLORS) ?: false,
                appIcon = AppIcon.fromId(p.readSafely(Keys.APP_ICON)),
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.storageValue }
    }

    suspend fun setManualAccent(color: String, light: String) {
        dataStore.edit {
            it[Keys.ACCENT_COLOR] = color
            it[Keys.ACCENT_LIGHT] = light
            // A preset/single-colour pick discards an older custom palette.
            it.remove(Keys.ACCENT_CONTAINER)
            it.remove(Keys.ACCENT_HIGHLIGHT)
            it[Keys.ACCENT_MODE] = AccentMode.MANUAL.storageValue
            it[Keys.LASTWAVE_THEME] = false
        }
    }

    /** Four explicitly picked roles: accent, light tone, dark container,
     *  highlight. Stored as-is so the UI can show exactly what was chosen. */
    suspend fun setManualAccent(
        color: String,
        light: String,
        container: String,
        highlight: String,
    ) {
        dataStore.edit {
            it[Keys.ACCENT_COLOR] = color
            it[Keys.ACCENT_LIGHT] = light
            it[Keys.ACCENT_CONTAINER] = container
            it[Keys.ACCENT_HIGHLIGHT] = highlight
            it[Keys.ACCENT_MODE] = AccentMode.MANUAL.storageValue
            it[Keys.LASTWAVE_THEME] = false
        }
    }

    suspend fun setMode(mode: AccentMode) {
        dataStore.edit {
            it[Keys.ACCENT_MODE] = mode.storageValue
            if (mode == AccentMode.DYNAMIC || mode == AccentMode.MONOCHROME) {
                it[Keys.LASTWAVE_THEME] = false
            }
        }
    }

    suspend fun setAmoled(enabled: Boolean) {
        dataStore.edit { it[Keys.AMOLED] = enabled }
    }

    suspend fun setLiquidGlass(enabled: Boolean) {
        dataStore.edit { it[Keys.LIQUID_GLASS] = enabled }
    }

    suspend fun setGlassSurface(surface: GlassSurface, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.GLASS_SURFACES]
                ?.split(",")?.filter { it.isNotBlank() }?.toSet()
                ?: GlassSurface.allIds
            val next = if (enabled) current + surface.id else current - surface.id
            prefs[Keys.GLASS_SURFACES] = next.joinToString(",")
        }
    }

    suspend fun setMd3NavBar(enabled: Boolean) {
        dataStore.edit { it[Keys.MD3_NAV_BAR] = enabled }
    }

    suspend fun setHideNavTabs(enabled: Boolean) {
        dataStore.edit { it[Keys.HIDE_NAV_TABS] = enabled }
    }

    suspend fun setUiHaptics(enabled: Boolean) {
        dataStore.edit { it[Keys.UI_HAPTICS] = enabled }
    }

    suspend fun setLegacyColors(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_LEGACY_COLORS] = enabled }
    }

    suspend fun setLastWaveTheme(enabled: Boolean) {
        dataStore.edit {
            it[Keys.LASTWAVE_THEME] = enabled
            if (enabled) {
                it[Keys.ACCENT_COLOR] = "#C9FB00"
                it[Keys.ACCENT_LIGHT] = "#C9FB00"
                it.remove(Keys.ACCENT_CONTAINER)
                it.remove(Keys.ACCENT_HIGHLIGHT)
                it[Keys.ACCENT_MODE] = AccentMode.MANUAL.storageValue
            }
        }
    }

    suspend fun setAppIcon(icon: AppIcon) {
        dataStore.edit { it[Keys.APP_ICON] = icon.id }
    }
}
