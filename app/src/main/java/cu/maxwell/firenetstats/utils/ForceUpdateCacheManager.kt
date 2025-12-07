package cu.maxwell.firenetstats.utils

import android.content.Context
import android.util.Log

/**
 * Utilitario para gestionar el cache de verificación de actualizaciones forzadas
 */
object ForceUpdateCacheManager {

    private const val TAG = "ForceUpdateCacheManager"
    private const val PREFS_NAME = "force_update_cache"
    private const val LAST_CHECK_TIME_KEY = "last_check_time"

    /**
     * Resetea el timestamp de cache para forzar una verificación en el próximo inicio
     * Útil para testing y debugging
     */
    fun resetCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(LAST_CHECK_TIME_KEY).apply()
        Log.d(TAG, "Cache de actualización forzada reseteado - próxima verificación será inmediata")
    }

    /**
     * Obtiene el tiempo desde la última verificación en horas
     */
    fun getTimeSinceLastCheck(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(LAST_CHECK_TIME_KEY, 0)
        val currentTime = System.currentTimeMillis()
        val timePassed = currentTime - lastCheckTime
        return timePassed / 1000 / 60 / 60 // Convertir a horas
    }

    /**
     * Retorna true si el cache es válido (menos de 24 horas)
     */
    fun isCacheValid(context: Context): Boolean {
        return getTimeSinceLastCheck(context) < 24
    }

    /**
     * Obtiene la hora de la última verificación formateada
     */
    fun getLastCheckTime(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(LAST_CHECK_TIME_KEY, 0)
        return if (lastCheckTime == 0L) {
            "Nunca"
        } else {
            val formatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
            formatter.format(java.util.Date(lastCheckTime))
        }
    }
}
