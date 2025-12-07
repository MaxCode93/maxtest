package cu.maxwell.firenetstats

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cu.maxwell.firenetstats.firewall.NetStatsFirewallVpnService

class UnifiedNotificationService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "FireNetStatsUnifiedChannel"
        const val ACTION_UPDATE_STATUS = "cu.maxwell.firenetstats.UPDATE_NOTIFICATION_STATUS"

        fun updateNotificationStatus(context: Context, widgetActive: Boolean, firewallActive: Boolean) {
            val intent = Intent(ACTION_UPDATE_STATUS).apply {
                putExtra("widget_active", widgetActive)
                putExtra("firewall_active", firewallActive)
            }
            context.sendBroadcast(intent)
        }
    }

    private var widgetActive = false
    private var firewallActive = false
    private var lastFirewallState = false
    private var lastUpdateTime = 0L
    private var isNotificationActive = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val currentTime = System.currentTimeMillis()

            when (intent.action) {
                ACTION_UPDATE_STATUS -> {
                    widgetActive = intent.getBooleanExtra("widget_active", false)
                    firewallActive = intent.getBooleanExtra("firewall_active", false)
                    updateNotification()
                }
                NetStatsFirewallVpnService.ACTION_STATE_CHANGED -> {
                    val newFirewallState = intent.getBooleanExtra("RUNNING", false)

                    // Evitar actualizaciones rápidas del mismo estado (reconfiguración del VPN)
                    if (newFirewallState == lastFirewallState && (currentTime - lastUpdateTime) < 500) {
                        return // Ignorar actualización duplicada
                    }

                    firewallActive = newFirewallState
                    lastFirewallState = newFirewallState
                    lastUpdateTime = currentTime
                    updateNotification()
                }
                "cu.maxwell.firenetstats.SERVICE_STATE_CHANGED" -> {
                    // Revalidar el estado actual en lugar de solo confiar en el extra del intent
                    widgetActive = FloatingWidgetService.isServiceRunning(context)
                    // También actualizar el estado del firewall por si cambió
                    firewallActive = isFirewallServiceRunning()
                    updateNotification()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()

        // Resetear estado cuando el servicio se reinicia
        isNotificationActive = false

        createNotificationChannel()

        // Registrar receiver para actualizaciones de estado
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_STATUS)
            addAction(NetStatsFirewallVpnService.ACTION_STATE_CHANGED)
            addAction("cu.maxwell.firenetstats.SERVICE_STATE_CHANGED")
        }
        registerReceiver(statusReceiver, filter)

        // Verificar estado inicial
        checkInitialStatus()
        
        // Solo iniciar como foreground si hay servicios activos
        if (widgetActive || firewallActive) {
            val notification = createNotification()
            try {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
                isNotificationActive = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Receiver no estaba registrado
        }
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun checkInitialStatus() {
        // Verificar si el widget está activo
        widgetActive = FloatingWidgetService.isServiceRunning(this)

        // Verificar si el firewall está activo
        firewallActive = isFirewallServiceRunning()
    }

    private fun isFirewallServiceRunning(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == NetStatsFirewallVpnService::class.java.name }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FireNetStats Services",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado de servicios de FireNetStats"
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        // Revalidar estado actual de servicios (puede cambiar sin broadcast)
        widgetActive = FloatingWidgetService.isServiceRunning(this)
        firewallActive = isFirewallServiceRunning()

        // Si no hay servicios activos, detener la notificación
        if (!widgetActive && !firewallActive) {
            try {
                @Suppress("DEPRECATION")
                stopForeground(true)
                isNotificationActive = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Detener el servicio
            stopSelf()
            return
        }

        // Si hay servicios activos, asegurar que la notificación esté visible
        val notification = createNotification()

        try {
            if (!isNotificationActive) {
                // Primera vez que se muestra la notificación
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
                isNotificationActive = true
            } else {
                // Actualizar notificación existente
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // En caso de error, intentar una última vez
            try {
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val (title, text) = when {
            widgetActive && firewallActive -> {
                "FireNetStats Activo" to "Widget flotante y Firewall activos"
            }
            widgetActive -> {
                "FireNetStats Widget" to "Widget flotante activo"
            }
            firewallActive -> {
                "FireNetStats Firewall" to "Firewall activo"
            }
            else -> {
                // Esto nunca debería ocurrir ya que updateNotification() se detiene si ambos están inactivos
                "FireNetStats" to "Sin servicios activos"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}