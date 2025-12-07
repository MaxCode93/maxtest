package cu.maxwell.firenetstats.firewall

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log

class ForegroundAppMonitor(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val packageManager = context.packageManager

    fun isUsageAccessGranted(): Boolean {
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT &&
                    context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
    }

    fun getForegroundPackageName(): String? {
        if (!isUsageAccessGranted()) {
            Log.w("ForegroundAppMonitor", "Usage access not granted; cannot detect foreground app")
            return null
        }

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 10_000, now)
        var lastPackage: String? = null
        val usageEvent = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(usageEvent)
            if (usageEvent.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        usageEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            ) {
                lastPackage = usageEvent.packageName
            }
        }

        return lastPackage
    }

    fun isForegroundApp(packageName: String): Boolean {
        return getForegroundPackageName() == packageName
    }

    fun isSystemApp(packageName: String): Boolean {
        return try {
            val app = packageManager.getApplicationInfo(packageName, 0)
            (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }
}
