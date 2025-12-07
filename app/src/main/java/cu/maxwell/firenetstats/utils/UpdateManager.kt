package cu.maxwell.firenetstats.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

class UpdateManager(private val context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
    private val repository = UpdateRepository()

    private fun getCurrentVersionInfo(): Pair<String, Int> {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            Pair(packageInfo.versionName ?: "1.0", packageInfo.versionCode)
        } catch (e: Exception) {
            Pair("1.0", 12) // fallback
        }
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val KEY_UPDATE_INFO = "update_info"
        private const val KEY_UPDATE_AVAILABLE = "update_available"
        private const val KEY_VERSION_NAME = "version_name"
        private const val KEY_VERSION_CODE = "version_code"
        private const val KEY_CHANGELOG = "changelog"
        private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L // 24 horas
    }

    suspend fun checkForUpdates(forceCheck: Boolean = false): UpdateState {
        return withContext(Dispatchers.IO) {
            // Verificar si es necesario hacer una búsqueda
            if (!forceCheck && !shouldCheckForUpdates()) {
                return@withContext getLastSavedUpdateState()
            }

            val remoteUpdate = repository.fetchLatestRelease() ?:
                return@withContext UpdateState(error = "Error al conectar con GitHub")

            val (currentVersionName, currentVersionCode) = getCurrentVersionInfo()

            val hasUpdate = remoteUpdate.versionCode > currentVersionCode

            // Guardar información
            saveUpdateInfo(remoteUpdate, hasUpdate)

            UpdateState(
                available = hasUpdate,
                currentVersion = currentVersionName,
                latestVersion = remoteUpdate.versionName,
                changelog = remoteUpdate.changelog,
                apkListsUrl = remoteUpdate.apkListsUrl
            )
        }
    }

    private fun shouldCheckForUpdates(): Boolean {
        val lastCheck = sharedPreferences.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        return (now - lastCheck) > CHECK_INTERVAL
    }

    private fun saveUpdateInfo(updateInfo: UpdateInfo, isAvailable: Boolean) {
        sharedPreferences.edit().apply {
            putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            putBoolean(KEY_UPDATE_AVAILABLE, isAvailable)
            putString(KEY_VERSION_NAME, updateInfo.versionName)
            putInt(KEY_VERSION_CODE, updateInfo.versionCode)
            putString(KEY_CHANGELOG, updateInfo.changelog)
            apply()
        }
    }

    private fun getLastSavedUpdateState(): UpdateState {
        val available = sharedPreferences.getBoolean(KEY_UPDATE_AVAILABLE, false)
        val versionName = sharedPreferences.getString(KEY_VERSION_NAME, "") ?: ""
        val changelog = sharedPreferences.getString(KEY_CHANGELOG, "") ?: ""

        val (currentVersionName, currentVersionCode) = getCurrentVersionInfo()

        val savedVersionCode = sharedPreferences.getInt(KEY_VERSION_CODE, 0)

        // Por defecto, no hay actualización disponible
        val actualAvailable = if (savedVersionCode == 0) {
            // Si no hay datos guardados, NO hay actualización
            false
        } else if (savedVersionCode == currentVersionCode) {
            // Si la versión guardada es igual a la actual, NO hay actualización
            false
        } else if (savedVersionCode < currentVersionCode) {
            // Si la versión guardada es menor, el caché está obsoleto, NO hay actualización
            false
        } else {
            // Solo si savedVersionCode > currentVersionCode hay actualización
            available
        }

        return UpdateState(
            available = actualAvailable,
            currentVersion = currentVersionName,
            latestVersion = versionName,
            changelog = changelog,
            apkListsUrl = "https://www.apklis.cu/application/cu.maxwell.firenetstats"
        )
    }

    fun isUpdateAvailable(): Boolean {
        return sharedPreferences.getBoolean(KEY_UPDATE_AVAILABLE, false)
    }

    fun getUpdateInfo(): UpdateState {
        return getLastSavedUpdateState()
    }

    fun clearUpdateInfo() {
        sharedPreferences.edit().apply {
            remove(KEY_UPDATE_AVAILABLE)
            remove(KEY_VERSION_NAME)
            remove(KEY_VERSION_CODE)
            remove(KEY_CHANGELOG)
            apply()
        }
    }
}
