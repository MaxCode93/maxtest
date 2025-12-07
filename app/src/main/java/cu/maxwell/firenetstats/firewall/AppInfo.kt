package cu.maxwell.firenetstats.firewall

import android.graphics.drawable.Drawable

/**
 * Data class to hold all information about an app.
 */
data class AppInfo(
    val appName: String,
    val packageName: String,
    var appIcon: Drawable?,
    val isSystemApp: Boolean,
    val hasInternetPermission: Boolean,
    var isWifiBlocked: Boolean = false,
    var isDataBlocked: Boolean = false,
    var isSelected: Boolean = false, // Property for batch selection
    var downloadBytes: Long = 0L,
    var uploadBytes: Long = 0L,
    var totalBytes: Long = 0L
) {
    val isBlocked: Boolean
        get() = isWifiBlocked || isDataBlocked
}