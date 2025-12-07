package cu.maxwell.firenetstats.firewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cu.maxwell.firenetstats.settings.InitializationManager

/**
 * BroadcastReceiver que escucha la instalación de nuevas apps
 * y automáticamente las bloquea en ambos modos (WiFi y Data)
 */
class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        // Escuchar eventos de instalación y actualización de paquetes
        if (intent.action == Intent.ACTION_PACKAGE_ADDED || intent.action == Intent.ACTION_PACKAGE_REPLACED) {
            val packageName = intent.data?.schemeSpecificPart ?: return
            
            // No procesar si es una actualización de sistema (intent.getBooleanExtra("android.intent.extra.REPLACING", false))
            if (intent.getBooleanExtra("android.intent.extra.REPLACING", false) && 
                intent.action == Intent.ACTION_PACKAGE_REPLACED) {
                return // Es una actualización, no una nueva instalación
            }

            // Bloquear la app recién instalada
            blockNewlyInstalledApp(context, packageName)
        }
    }

    private fun blockNewlyInstalledApp(context: Context, packageName: String) {
        try {
            val prefs = NetStatsFirewallPreferences(context)

            // Bloquear en WiFi
            prefs.setWifiBlocked(NetStatsFirewallMode.VPN, packageName, true)

            // Bloquear en Data
            prefs.setDataBlocked(NetStatsFirewallMode.VPN, packageName, true)

            android.util.Log.d("PackageInstallReceiver", "App bloqueada automáticamente: $packageName")
        } catch (e: Exception) {
            android.util.Log.e("PackageInstallReceiver", "Error al bloquear app: $packageName", e)
        }
    }
}
