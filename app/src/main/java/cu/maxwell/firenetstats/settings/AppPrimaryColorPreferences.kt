package cu.maxwell.firenetstats.settings

import android.content.Context
import android.content.SharedPreferences
import cu.maxwell.firenetstats.R

class AppPrimaryColorPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_primary_color_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_PRIMARY_COLOR = "primary_color_index"
        const val COLOR_DEFAULT = 0
        const val COLOR_RED = 1
        const val COLOR_GREEN = 2
        const val COLOR_YELLOW = 3
        const val COLOR_PURPLE = 4
        const val COLOR_ORANGE = 5
        const val COLOR_BLUE = 6
    }
    fun getPrimaryColorIndex(): Int {
        return prefs.getInt(KEY_PRIMARY_COLOR, COLOR_DEFAULT)
    }
    fun setPrimaryColorIndex(index: Int) {
        prefs.edit().putInt(KEY_PRIMARY_COLOR, index).apply()
    }

    fun getColorName(index: Int): String {
        return when (index) {
            COLOR_DEFAULT -> "Default"
            COLOR_RED -> "Rojo"
            COLOR_GREEN -> "Verde"
            COLOR_YELLOW -> "Amarillo"
            COLOR_PURPLE -> "Morado"
            COLOR_ORANGE -> "Naranja"
            COLOR_BLUE -> "Azul"
            else -> "Default"
        }
    }

    // Obtener el resource ID del style para el color
    fun getStyleResId(index: Int): Int {
        return when (index) {
            COLOR_DEFAULT -> R.style.PrimaryColorDefault
            COLOR_RED -> R.style.PrimaryColorRed
            COLOR_GREEN -> R.style.PrimaryColorGreen
            COLOR_YELLOW -> R.style.PrimaryColorYellow
            COLOR_PURPLE -> R.style.PrimaryColorPurple
            COLOR_ORANGE -> R.style.PrimaryColorOrange
            COLOR_BLUE -> R.style.PrimaryColorBlue
            else -> R.style.PrimaryColorDefault
        }
    }
}