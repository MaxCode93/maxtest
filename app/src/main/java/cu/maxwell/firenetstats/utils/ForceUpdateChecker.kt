package cu.maxwell.firenetstats.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ForceUpdateChecker {

    private const val TAG = "ForceUpdateChecker"
    private const val PREFS_NAME = "force_update_cache"
    private const val LAST_CHECK_TIME_KEY = "last_check_time"
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 horas en milisegundos

    interface OnForceUpdateListener {
        fun onForceUpdateChecked(result: ForceUpdateManager.ForceUpdateResult)
    }

    /**
     * Verifica si debe realizar una nueva comprobación de actualización forzada
     * Retorna true si han pasado más de 24 horas desde la última comprobación
     */
    private fun shouldCheckUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(LAST_CHECK_TIME_KEY, 0)
        val currentTime = System.currentTimeMillis()
        val timePassed = currentTime - lastCheckTime

        val shouldCheck = timePassed >= CACHE_DURATION_MS
        Log.d(TAG, "Últimas 24h: $shouldCheck (hace ${timePassed / 1000 / 60 / 60} horas)")
        return shouldCheck
    }

    /**
     * Actualiza el timestamp de la última comprobación
     */
    private fun updateLastCheckTime(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(LAST_CHECK_TIME_KEY, System.currentTimeMillis()).apply()
        Log.d(TAG, "Timestamp de verificación actualizado")
    }

    /**
     * Comprueba la actualización forzada solo si han pasado 24 horas desde la última comprobación
     */
    fun checkForceUpdate(
        context: Context,
        listener: OnForceUpdateListener? = null
    ) {
        // Verificar si debemos hacer la comprobación
        if (!shouldCheckUpdate(context)) {
            Log.d(TAG, "Cache válido: verificación reciente encontrada, omitiendo check de actualización forzada")
            listener?.onForceUpdateChecked(
                ForceUpdateManager.ForceUpdateResult(
                    isForced = false,
                    message = "",
                    updateUrl = "",
                    currentVersion = "",
                    error = null
                )
            )
            return
        }

        Log.d(TAG, "Iniciando verificación de actualización forzada (> 24h desde última check)")

        val manager = ForceUpdateManager(context)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = manager.checkForceUpdate()
                
                // Actualizar timestamp solo después de una comprobación exitosa
                updateLastCheckTime(context)
                
                Log.d(TAG, "Verificación completada. Forzada: ${result.isForced}")
                listener?.onForceUpdateChecked(result)

            } catch (e: Exception) {
                Log.e(TAG, "Error en verificación de fuerza actualización: ${e.message}", e)
                listener?.onForceUpdateChecked(
                    ForceUpdateManager.ForceUpdateResult(
                        isForced = false,
                        message = "",
                        updateUrl = "",
                        currentVersion = "",
                        error = e.message
                    )
                )
            }
        }
    }
}
