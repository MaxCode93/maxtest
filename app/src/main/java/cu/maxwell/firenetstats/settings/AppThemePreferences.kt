package cu.maxwell.firenetstats.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class AppThemePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_theme_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        const val THEME_DARK = 0
        const val THEME_LIGHT = 1
        const val THEME_AUTO = 2
    }

    // Obtener el modo de tema guardado (por defecto: DARK)
    fun getThemeMode(): Int {
        return prefs.getInt(KEY_THEME_MODE, THEME_DARK)
    }

    // Guardar el modo de tema
    fun setThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    // Aplicar el tema usando AppCompatDelegate
    fun applyTheme() {
        val mode = getThemeMode()
        when (mode) {
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_AUTO -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    // Convertir índice del spinner a modo de tema
    fun spinnerPositionToThemeMode(position: Int): Int {
        return when (position) {
            0 -> THEME_AUTO
            1 -> THEME_LIGHT
            2 -> THEME_DARK
            else -> THEME_DARK
        }
    }

    // Convertir modo de tema a índice del spinner
    fun themeModeToSpinnerPosition(mode: Int): Int {
        return when (mode) {
            THEME_AUTO -> 0
            THEME_LIGHT -> 1
            THEME_DARK -> 2
            else -> 2
        }
    }
}
