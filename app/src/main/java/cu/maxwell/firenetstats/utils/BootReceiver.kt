package cu.maxwell.firenetstats.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cu.maxwell.firenetstats.FloatingWidgetService
import cu.maxwell.firenetstats.firewall.NetStatsFirewallVpnService
import cu.maxwell.firenetstats.settings.AppStartupPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        // Solo procesar si es BOOT_COMPLETED
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d("BootReceiver", "System boot detected, checking startup preferences")

        val startupPrefs = AppStartupPreferences(context)

        // Si no está habilitado el inicio con sistema, no hacer nada
        if (!startupPrefs.isStartupEnabled()) {
            Log.d("BootReceiver", "Startup is disabled, exiting")
            return
        }

        Log.d("BootReceiver", "Startup is enabled, checking components")

        // Iniciar Widget si está configurado
        if (startupPrefs.shouldStartWidget()) {
            Log.d("BootReceiver", "Starting FloatingWidgetService")
            try {
                val widgetIntent = Intent(context, FloatingWidgetService::class.java)
                context.startService(widgetIntent)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error starting FloatingWidgetService", e)
            }
        }

        // Iniciar Firewall si está configurado
        if (startupPrefs.shouldStartFirewall()) {
            Log.d("BootReceiver", "Starting NetStatsFirewallVpnService")
            try {
                val firewallIntent = Intent(context, NetStatsFirewallVpnService::class.java)
                context.startService(firewallIntent)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error starting NetStatsFirewallVpnService", e)
            }
        }

        Log.d("BootReceiver", "Boot startup process completed")
    }
}
