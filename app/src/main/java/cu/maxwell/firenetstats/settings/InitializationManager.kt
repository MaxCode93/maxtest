package cu.maxwell.firenetstats.settings

import android.content.Context
import cu.maxwell.firenetstats.firewall.NetStatsFirewallMode
import cu.maxwell.firenetstats.firewall.NetStatsFirewallPreferences
import androidx.core.content.edit

class InitializationManager(private val context: Context) {

    private val prefs = NetStatsFirewallPreferences(context)
    private val sharedPrefs = context.getSharedPreferences("app_init_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FIRST_RUN = "first_run_completed"
    }

    /**
     * Realiza inicialización por primera vez si es necesario
     * - Bloquea todas las apps existentes en ambos modos (WiFi y Data)
     */
    fun performFirstRunInitializationIfNeeded() {
        if (isFirstRun()) {
            blockAllAppsOnFirstRun()
            markFirstRunAsCompleted()
        }
    }

    private fun isFirstRun(): Boolean {
        return !sharedPrefs.getBoolean(KEY_FIRST_RUN, false)
    }

    private fun blockAllAppsOnFirstRun() {
        try {
            val packageManager = context.packageManager
            val packages = packageManager.getInstalledPackages(0)
            val ownPackageName = context.packageName

            // Bloquear todas las apps en modo VPN, excepto la propia
            for (pkgInfo in packages) {
                val packageName = pkgInfo.packageName

                // Excluir la propia app
                if (packageName == ownPackageName) {
                    continue
                }

                // Bloquear en WiFi
                prefs.setWifiBlocked(NetStatsFirewallMode.VPN, packageName, true)

                // Bloquear en Data
                prefs.setDataBlocked(NetStatsFirewallMode.VPN, packageName, true)
            }
        } catch (e: Exception) {
            // Log error pero no crashear
            e.printStackTrace()
        }
    }

    private fun markFirstRunAsCompleted() {
        sharedPrefs.edit(commit = true) { putBoolean(KEY_FIRST_RUN, true) }
    }
}
