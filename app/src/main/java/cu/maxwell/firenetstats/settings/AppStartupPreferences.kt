package cu.maxwell.firenetstats.settings

import android.content.Context
import android.content.SharedPreferences

class AppStartupPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_startup_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_STARTUP_ENABLED = "startup_enabled"
        private const val KEY_STARTUP_COMPONENTS = "startup_components"
        
        // Componentes a iniciar
        const val COMPONENT_WIDGET = 0
        const val COMPONENT_FIREWALL = 1
        const val COMPONENT_BOTH = 2
    }

    // Obtener si el inicio con sistema está habilitado (por defecto: false)
    fun isStartupEnabled(): Boolean {
        return prefs.getBoolean(KEY_STARTUP_ENABLED, false)
    }

    // Habilitar/deshabilitar inicio con sistema
    fun setStartupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STARTUP_ENABLED, enabled).apply()
    }

    // Obtener qué componentes inician (por defecto: WIDGET)
    fun getStartupComponent(): Int {
        return prefs.getInt(KEY_STARTUP_COMPONENTS, COMPONENT_WIDGET)
    }

    // Establecer qué componentes inician
    fun setStartupComponent(component: Int) {
        prefs.edit().putInt(KEY_STARTUP_COMPONENTS, component).apply()
    }

    // Verificar si el widget debe iniciar
    fun shouldStartWidget(): Boolean {
        return isStartupEnabled() && (
            getStartupComponent() == COMPONENT_WIDGET || 
            getStartupComponent() == COMPONENT_BOTH
        )
    }

    // Verificar si el firewall debe iniciar
    fun shouldStartFirewall(): Boolean {
        return isStartupEnabled() && (
            getStartupComponent() == COMPONENT_FIREWALL || 
            getStartupComponent() == COMPONENT_BOTH
        )
    }
}
