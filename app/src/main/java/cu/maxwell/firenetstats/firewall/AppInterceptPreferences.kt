package cu.maxwell.firenetstats.firewall

import android.content.Context
import android.content.SharedPreferences

class AppInterceptPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_intercept_prefs",
        Context.MODE_PRIVATE
    )

    // Habilitar/deshabilitar notificaciones de intentos de acceso
    fun setInterceptNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("intercept_notifications_enabled", enabled).apply()
    }

    fun isInterceptNotificationsEnabled(): Boolean {
        return prefs.getBoolean("intercept_notifications_enabled", true) // Habilitado por defecto
    }
}
