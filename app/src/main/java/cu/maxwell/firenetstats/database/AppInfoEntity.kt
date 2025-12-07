package cu.maxwell.firenetstats.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_info")
data class AppInfoEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val hasInternetPermission: Boolean,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val isWifiBlocked: Boolean,
    val isDataBlocked: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
