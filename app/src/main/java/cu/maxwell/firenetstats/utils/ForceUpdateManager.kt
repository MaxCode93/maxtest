package cu.maxwell.firenetstats.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ForceUpdateManager(private val context: Context) {

    private companion object {
        const val TAG = "ForceUpdateManager"
        const val PREFS_NAME = "force_update_prefs"
        const val KEY_LAST_CHECK_TIME = "last_check_time"
        const val KEY_IS_FORCED = "is_forced_update"
        const val KEY_FORCE_MESSAGE = "force_message"
        const val KEY_UPDATE_URL = "update_url"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun checkForceUpdate(): ForceUpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                // Obtener versión actual
                val currentVersion = getCurrentAppVersion()

                // Intentar descargar archivo FORCE_UPDATE.fns
                val forceUpdateInfo = ForceUpdateRepository.fetchForceUpdateInfo()

                if (forceUpdateInfo == null) {
                    //Log.w(TAG, "No se pudo descargar FORCE_UPDATE.fns, usando caché")
                    return@withContext getCachedResult()
                }

                // Actualizar caché con el contenido descargado
                updateCacheWithDownloadedInfo(forceUpdateInfo)

                // Determinar si mostrar diálogo
                val isForced = shouldForceUpdate(forceUpdateInfo, currentVersion)

                //Log.d(TAG, "Versión actual: $currentVersion, Forzada: $isForced, blocked_versions: ${forceUpdateInfo.blockedVersions}")

                ForceUpdateResult(
                    isForced = isForced,
                    message = forceUpdateInfo.message,
                    updateUrl = forceUpdateInfo.updateUrl,
                    currentVersion = currentVersion,
                    error = null
                )

            } catch (e: Exception) {
                //Log.e(TAG, "Error verificando actualización forzada: ${e.message}", e)
                getCachedResult()
            }
        }
    }

    private fun shouldForceUpdate(forceInfo: ForceUpdateRepository.ForceUpdateInfo, currentVersion: String): Boolean {
        if (!forceInfo.forceUpdate) {
            //Log.d(TAG, "force_update = false → No se fuerza actualización")
            return false
        }

        if (forceInfo.blockedVersions.contains("all")) {
            //Log.d(TAG, "blocked_versions = all → Se fuerza para TODAS las versiones")
            return true
        }

        val isBlocked = forceInfo.blockedVersions.contains(currentVersion)
        /*Log.d(TAG,
            if (isBlocked) "Versión $currentVersion está bloqueada → Forzar actualización"
            else "Versión $currentVersion NO está bloqueada → No forzar"
        )*/
        return isBlocked
    }

    private fun getCurrentAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            //Log.e(TAG, "Error obteniendo versión actual: ${e.message}")
            "1.0.0"
        }
    }

    private fun cacheForceUpdateResult(
        forced: Boolean,
        message: String,
        updateUrl: String
    ) {
        prefs.edit().apply {
            putBoolean(KEY_IS_FORCED, forced)
            putString(KEY_FORCE_MESSAGE, message)
            putString(KEY_UPDATE_URL, updateUrl)
            apply()
        }
    }

    private fun clearForceUpdateCache() {
        prefs.edit().apply {
            remove(KEY_IS_FORCED)
            remove(KEY_FORCE_MESSAGE)
            remove(KEY_UPDATE_URL)
            apply()
        }
    }

    private fun getCachedResult(): ForceUpdateResult {
        val isForced = prefs.getBoolean(KEY_IS_FORCED, false)
        val message = prefs.getString(KEY_FORCE_MESSAGE, "Actualización requerida") ?: "Actualización requerida"
        val updateUrl = prefs.getString(KEY_UPDATE_URL, "") ?: ""
        val currentVersion = getCurrentAppVersion()

        return ForceUpdateResult(
            isForced = isForced,
            message = message,
            updateUrl = updateUrl,
            currentVersion = currentVersion,
            error = null
        )
    }

	private fun updateCacheWithDownloadedInfo(forceUpdateInfo: ForceUpdateRepository.ForceUpdateInfo) {
		val shouldForceUpdate = if (forceUpdateInfo.forceUpdate) {
			val currentVersion = getCurrentAppVersion()
			if (forceUpdateInfo.blockedVersions.contains("all")) {
				true
			} else {
				forceUpdateInfo.blockedVersions.contains(currentVersion)
			}
		} else {
			false
		}

		// Actualizar caché
		if (shouldForceUpdate) {
			cacheForceUpdateResult(
				forced = true,
				message = forceUpdateInfo.message,
				updateUrl = forceUpdateInfo.updateUrl
			)
		} else {
			clearForceUpdateCache()
		}
		prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
	}

    data class ForceUpdateResult(
        val isForced: Boolean,
        val message: String,
        val updateUrl: String,
        val currentVersion: String,
        val error: String? = null
    )
}
