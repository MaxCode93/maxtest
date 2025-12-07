package cu.maxwell.firenetstats.database

import android.content.Context
import cu.maxwell.firenetstats.firewall.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppCacheManager(context: Context) {

    private val dao = FireNetStatsDatabase.getDatabase(context).appInfoDao()
    private val iconCacheManager = IconCacheManager(context)

    suspend fun getAllAppsFromCache(): List<AppInfo> {
        return withContext(Dispatchers.IO) {
            dao.getAllApps().map { entity ->
                AppInfo(
                    appName = entity.appName,
                    packageName = entity.packageName,
                    appIcon = iconCacheManager.getIcon(entity.packageName),
                    isSystemApp = entity.isSystemApp,
                    hasInternetPermission = entity.hasInternetPermission,
                    isWifiBlocked = entity.isWifiBlocked,
                    isDataBlocked = entity.isDataBlocked,
                    isSelected = false,
                    downloadBytes = entity.downloadBytes,
                    uploadBytes = entity.uploadBytes,
                    totalBytes = entity.downloadBytes + entity.uploadBytes
                )
            }
        }
    }

    suspend fun saveAppsToCache(apps: List<AppInfo>) {
        withContext(Dispatchers.IO) {
            // Guardar iconos PRIMERO (secuencial, esperando a que terminen)
            for (app in apps) {
                iconCacheManager.saveIcon(app.packageName, app.appIcon)
            }
            
            // Luego guardar metadata en BD
            dao.deleteAll()
            val entities = apps.map { app ->
                AppInfoEntity(
                    packageName = app.packageName,
                    appName = app.appName,
                    isSystemApp = app.isSystemApp,
                    hasInternetPermission = app.hasInternetPermission,
                    downloadBytes = app.downloadBytes,
                    uploadBytes = app.uploadBytes,
                    isWifiBlocked = app.isWifiBlocked,
                    isDataBlocked = app.isDataBlocked
                )
            }
            dao.insertAll(entities)
        }
    }

    suspend fun updateAppInCache(app: AppInfo) {
        withContext(Dispatchers.IO) {
            // Guardar icono
            iconCacheManager.saveIcon(app.packageName, app.appIcon)
            
            val entity = AppInfoEntity(
                packageName = app.packageName,
                appName = app.appName,
                isSystemApp = app.isSystemApp,
                hasInternetPermission = app.hasInternetPermission,
                downloadBytes = app.downloadBytes,
                uploadBytes = app.uploadBytes,
                isWifiBlocked = app.isWifiBlocked,
                isDataBlocked = app.isDataBlocked
            )
            dao.update(entity)
        }
    }

    suspend fun upsertApp(app: AppInfo) {
        withContext(Dispatchers.IO) {
            // Guardar icono
            iconCacheManager.saveIcon(app.packageName, app.appIcon)
            
            val entity = AppInfoEntity(
                packageName = app.packageName,
                appName = app.appName,
                isSystemApp = app.isSystemApp,
                hasInternetPermission = app.hasInternetPermission,
                downloadBytes = app.downloadBytes,
                uploadBytes = app.uploadBytes,
                isWifiBlocked = app.isWifiBlocked,
                isDataBlocked = app.isDataBlocked
            )
            dao.insert(entity)
        }
    }

    suspend fun updateBlockedState(packageName: String, isBlocked: Boolean) {
        withContext(Dispatchers.IO) {
            dao.getAppByPackage(packageName)?.let { entity ->
                dao.update(
                    entity.copy(
                        isWifiBlocked = isBlocked,
                        isDataBlocked = isBlocked
                    )
                )
            }
        }
    }

    suspend fun getAppCountFromCache(): Int {
        return withContext(Dispatchers.IO) {
            dao.getAppCount()
        }
    }

    suspend fun isCacheEmpty(): Boolean {
        return withContext(Dispatchers.IO) {
            dao.getAppCount() == 0
        }
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            dao.deleteAll()
            iconCacheManager.clearAllIcons()
        }
    }
}
