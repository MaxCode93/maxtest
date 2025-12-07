package cu.maxwell.firenetstats.firewall

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import cu.maxwell.firenetstats.FloatingWidgetService
import cu.maxwell.firenetstats.UnifiedNotificationService
import cu.maxwell.firenetstats.R
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("VpnServicePolicy")
class NetStatsFirewallVpnService : VpnService() {

    private val TAG = "NetStatsFirewallVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var foregroundAppMonitor: ForegroundAppMonitor
    private lateinit var interceptNotificationManager: InterceptNotificationManager
    private lateinit var appInterceptPreferences: AppInterceptPreferences
    private var packetInterceptor: VpnPacketInterceptor? = null

    private var isWifiActive: Boolean = false
    private var currentNetwork: Network? = null

    private val isReconfiguring = AtomicBoolean(false)
    private val vpnAddress: Inet4Address = InetAddress.getByName("10.1.10.1") as Inet4Address

    companion object {
        const val ACTION_STOP = "cu.maxwell.firenetstats.STOP_VPN"
        const val ACTION_REFRESH = "cu.maxwell.firenetstats.REFRESH_VPN"

        const val ACTION_CLEAR_NOTIFIED = "cu.maxwell.firenetstats.CLEAR_NOTIFIED"
        const val ACTION_STATE_CHANGED = "cu.maxwell.firenetstats.FIREWALL_STATE_CHANGED"
        const val ACTION_RULES_UPDATED = "cu.maxwell.firenetstats.FIREWALL_RULES_UPDATED"

        fun stopVpn(context: Context) {
            val intent = Intent(context, NetStatsFirewallVpnService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        private fun sendStateBroadcast(context: Context, isRunning: Boolean) {
            val intent = Intent(ACTION_STATE_CHANGED)
            intent.putExtra("RUNNING", isRunning)
            context.sendBroadcast(intent)
        }

        fun sendRulesUpdatedBroadcast(context: Context, packageName: String, isBlocked: Boolean) {
            val intent = Intent(ACTION_RULES_UPDATED)
            intent.putExtra("package_name", packageName)
            intent.putExtra("is_blocked", isBlocked)
            context.sendBroadcast(intent)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                Log.d(TAG, "NetworkCallback: onAvailable ignored VPN transport")
                return
            }

            Log.d(TAG, "NetworkCallback: onAvailable")
            currentNetwork = selectBaseNetwork()
            val oldWifiState = isWifiActive
            updateCurrentNetworkState()

            if (isWifiActive != oldWifiState) {
                Log.i(TAG, "Network type changed on-available. Reconfiguring VPN.")
                updateVpnConfiguration()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                Log.d(TAG, "NetworkCallback: onLost ignored VPN transport")
                return
            }

            Log.d(TAG, "NetworkCallback: onLost")
            if (network == currentNetwork) {
                currentNetwork = selectBaseNetwork()
                updateCurrentNetworkState()
                Log.w(TAG, "Network lost. Keeping VPN ready for when connectivity returns.")
                updateVpnConfiguration()
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                Log.d(TAG, "NetworkCallback: onCapabilitiesChanged ignored VPN transport")
                return
            }

            Log.d(TAG, "NetworkCallback: onCapabilitiesChanged")
            val oldWifiState = isWifiActive
            currentNetwork = selectBaseNetwork()
            updateCurrentNetworkState()

            if (isWifiActive != oldWifiState) {
                Log.i(TAG, "Network type changed. Reconfiguring VPN.")
                updateVpnConfiguration()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            foregroundAppMonitor = ForegroundAppMonitor(this)
            interceptNotificationManager = InterceptNotificationManager(this)
            appInterceptPreferences = AppInterceptPreferences(this)

            startUnifiedNotificationService()

            connectivityManager.registerDefaultNetworkCallback(networkCallback)

            sendStateBroadcast(this, true)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception initializing VPN service. Check permissions.", e)
            sendStateBroadcast(this, false)
            stopSelf()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Illegal state exception initializing VPN service.", e)
            sendStateBroadcast(this, false)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error initializing VPN service", e)
            sendStateBroadcast(this, false)
            stopSelf()
        }
    }

    private fun startUnifiedNotificationService() {
        if (!isUnifiedNotificationServiceRunning()) {
            val intent = Intent(this, UnifiedNotificationService::class.java)
            startService(intent)
        }
    }

    private fun isUnifiedNotificationServiceRunning(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return activityManager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == UnifiedNotificationService::class.java.name }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received stop command")
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                Log.i(TAG, "Received refresh command (from UI toggle)")
                updateVpnConfiguration()
            }
            ACTION_CLEAR_NOTIFIED -> {
                val pkg = intent.getStringExtra("package_name")
                if (!pkg.isNullOrEmpty()) {
                    Log.d(TAG, "Clearing notified flag for package: $pkg")
                    interceptNotificationManager.removeNotified(pkg)
                }
            }
            else -> {
                Log.i(TAG, "VPN Service starting (from master toggle or refresh)")
                updateCurrentNetworkState()
                updateVpnConfiguration()
            }
        }

        return START_STICKY
    }

    private fun checkAndNotifyInterceptAttempt(blockedPackage: String) {
        try {
            Log.d(TAG, "Checking intercept for blocked package: $blockedPackage")

            // Verificar si la app está en foreground
            val isForeground = foregroundAppMonitor.isForegroundApp(blockedPackage)
            Log.d(TAG, "App $blockedPackage foreground check: $isForeground")
            if (!isForeground) {
                Log.d(TAG, "App $blockedPackage is NOT in foreground, skipping notification")
                return
            }

            // Verificar si intercept notifications están habilitadas
            val notificationsEnabled = appInterceptPreferences.isInterceptNotificationsEnabled()
            Log.d(TAG, "Intercept notifications enabled: $notificationsEnabled")
            if (!notificationsEnabled) {
                Log.d(TAG, "Intercept notifications are disabled")
                return
            }

            // Verificar si ya fue notificada en esta sesión
            val alreadyNotified = interceptNotificationManager.hasBeenNotifiedInSession(blockedPackage)
            Log.d(TAG, "App $blockedPackage already notified in session: $alreadyNotified")
            if (alreadyNotified) {
                Log.d(TAG, "App $blockedPackage already notified in this session")
                return
            }

            // Obtener nombre de la app
            val appName = try {
                val appInfo = packageManager.getApplicationInfo(blockedPackage, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                Log.w(TAG, "Could not get app name for $blockedPackage", e)
                blockedPackage
            }

            Log.d(TAG, "All conditions met for $blockedPackage ($appName), showing notification")

            // Mostrar notificación
            interceptNotificationManager.showInterceptNotification(blockedPackage, appName)
            Log.d(TAG, "Showing intercept notification for $blockedPackage ($appName)")

        } catch (e: Exception) {
            Log.e(TAG, "Error checking/notifying intercept attempt for $blockedPackage", e)
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "VPN Service stopping...")

        packetInterceptor?.stop()

        stopSelf()
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface on stopVpn", e)
        }

        // Notificar que el servicio se ha detenido
        sendStateBroadcast(this, false)
        UnifiedNotificationService.updateNotificationStatus(
            this,
            FloatingWidgetService.isServiceRunning(this),
            false
        )

        stopUnifiedNotificationServiceIfIdle()
    }

    private fun updateCurrentNetworkState(): Boolean {
        currentNetwork = selectBaseNetwork()
        val capabilities = currentNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        isWifiActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellularActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        Log.d(TAG, "Network state updated: isWifi=$isWifiActive, isCellular=$isCellularActive")
        return isWifiActive || isCellularActive
    }

    private fun selectBaseNetwork(): Network? {
        val networks = connectivityManager.allNetworks

        val wifiNetwork = networks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        if (wifiNetwork != null) return wifiNetwork

        val cellularNetwork = networks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }

        return cellularNetwork
    }

    private fun updateVpnConfiguration() {
        if (!isReconfiguring.compareAndSet(false, true)) {
            Log.w(TAG, "Already reconfiguring, skipping call.")
            return
        }

        Log.d(TAG, "Starting VPN configuration...")

        try {
            val prefs = NetStatsFirewallPreferences(this)

            if (!updateCurrentNetworkState()) {
                Log.w(TAG, "updateVpnConfiguration called, but no active network. Proceeding without binding to one.")
            }

            val blockedPackages = prefs.getBlockedPackagesForNetwork(NetStatsFirewallMode.VPN, isWifiActive)
                .filter { it != packageName } // Excluir la propia app del firewall
                .toSet()
            packetInterceptor?.updateBlockedPackages(blockedPackages)

            if (blockedPackages.isEmpty()) {
                Log.i(TAG, "No apps are blocked for the current network ($isWifiActive). Closing VPN interface but keeping service running.")
                try {
                    packetInterceptor?.stop()
                    vpnInterface?.close()
                    vpnInterface = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing VPN interface when no apps blocked", e)
                }
                return
            }

            try {
                if (vpnInterface != null) {
                    packetInterceptor?.stop()
                    vpnInterface?.close()
                    vpnInterface = null // Set to null *after* closing
                    Log.d(TAG, "Closed old VPN interface.")
                }

                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(vpnAddress.hostAddress, 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")

                val activeNetwork = currentNetwork ?: selectBaseNetwork()
                if (activeNetwork != null) {
                    builder.setUnderlyingNetworks(arrayOf(activeNetwork))
                    Log.d(TAG, "Binding VPN to network: $activeNetwork")
                } else {
                    Log.w(TAG, "No active network to bind to.")
                }

                for (pkgName in blockedPackages) {
                    try {
                        builder.addAllowedApplication(pkgName)
                        Log.d(TAG, "BLOCKING (routing to VPN): $pkgName")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Package not found to block: $pkgName", e)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception adding allowed application: $pkgName", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error adding allowed application: $pkgName", e)
                    }
                }

                vpnInterface = builder.establish()

                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface. builder.establish() returned null. Stopping service.")
                    sendStateBroadcast(this, false)
                    stopSelf()
                    return
                } else {
                    Log.i(TAG, "VPN interface established successfully for ${if (isWifiActive) "Wi-Fi" else "Data"}. Blocked ${blockedPackages.size} apps.")
                    if (packetInterceptor == null) {
                        packetInterceptor = VpnPacketInterceptor(
                            this,
                            connectivityManager,
                            foregroundAppMonitor,
                            interceptNotificationManager,
                            appInterceptPreferences,
                            vpnAddress
                        ) { blockedPackage ->
                            checkAndNotifyInterceptAttempt(blockedPackage)
                        }
                    }

                    packetInterceptor?.updateBlockedPackages(blockedPackages)
                    vpnInterface?.let { interfaceDescriptor ->
                        packetInterceptor?.start(interfaceDescriptor)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception establishing VPN interface. Possible permission issue.", e)
                sendStateBroadcast(this, false)
                stopSelf()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Illegal state exception establishing VPN interface. VPN service may be in invalid state.", e)
                sendStateBroadcast(this, false)
                stopSelf()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Illegal argument exception establishing VPN interface. Check VPN configuration.", e)
                sendStateBroadcast(this, false)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error establishing VPN interface", e)
                sendStateBroadcast(this, false)
                stopSelf()
            }
        } finally {
            isReconfiguring.set(false)
            Log.d(TAG, "Finished VPN configuration.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed, unregistering callback and closing interface.")

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface in onDestroy", e)
        }

        packetInterceptor?.stop()
        packetInterceptor = null

        // Limpiar notificaciones de sesión cuando se detiene el firewall
        interceptNotificationManager.clearSessionNotifications()

        // Notificar que el servicio se ha detenido
        sendStateBroadcast(this, false)
        UnifiedNotificationService.updateNotificationStatus(
            this,
            FloatingWidgetService.isServiceRunning(this),
            false
        )

        stopUnifiedNotificationServiceIfIdle()
    }

    private fun stopUnifiedNotificationServiceIfIdle() {
        if (FloatingWidgetService.isServiceRunning(this)) return

        try {
            stopService(Intent(this, UnifiedNotificationService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping UnifiedNotificationService", e)
        }
    }
}