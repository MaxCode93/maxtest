package cu.maxwell.firenetstats.firewall

import cu.maxwell.firenetstats.firewall.AppInfo

object AppDataCache {
    private var cachedApps: List<AppInfo>? = null
    private var isCached = false

    fun setCachedApps(apps: List<AppInfo>) {
        cachedApps = apps
        isCached = true
    }

    fun getCachedApps(): List<AppInfo>? = cachedApps

    fun isCached(): Boolean = isCached

    fun clear() {
        cachedApps = null
        isCached = false
    }
}
