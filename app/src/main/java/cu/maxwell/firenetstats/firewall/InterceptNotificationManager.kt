package cu.maxwell.firenetstats.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import cu.maxwell.firenetstats.R

class InterceptNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "firewall_intercept_channel"
    
    // Tracking de apps ya notificadas en esta sesión
    private val notifiedAppsInSession = mutableSetOf<String>()

    init {
        createNotificationChannel()
    }

    private fun getAppIconBitmap(packageName: String): Bitmap? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawableToBitmap(drawable)
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Firewall Access Attempts",
                NotificationManager.IMPORTANCE_MAX // Heads-up notifications
            ).apply {
                description = "Notificaciones cuando apps bloqueadas intentan acceder a internet"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 100, 200, 100)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                lightColor = context.getColor(R.color.firewall_green)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showInterceptNotification(packageName: String, appName: String, networkType: String = "datos") {
        // Solo mostrar si la app no ha sido notificada en esta sesión
        if (notifiedAppsInSession.contains(packageName)) {
            return
        }

        notifiedAppsInSession.add(packageName)

        try {
            // Obtener ícono de la app
            val appIcon = getAppIconBitmap(packageName)

            // Intent para abrir el diálogo (acción por defecto)
            val mainIntent = Intent(context, InterceptDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                putExtra("package_name", packageName)
                putExtra("app_name", appName)
            }
            val mainPendingIntent = PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Intent para permitir directamente
            val allowIntent = Intent(context, InterceptDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                putExtra("package_name", packageName)
                putExtra("app_name", appName)
                putExtra("action", "allow")
            }
            val allowPendingIntent = PendingIntent.getActivity(
                context,
                packageName.hashCode() + 1,
                allowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Intent para bloquear directamente
            val blockIntent = Intent(context, InterceptDialogActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                putExtra("package_name", packageName)
                putExtra("app_name", appName)
                putExtra("action", "block")
            }
            val blockPendingIntent = PendingIntent.getActivity(
                context,
                packageName.hashCode() + 2,
                blockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle("$appName intenta acceder")
                .setContentText("Accion Requerida • Toca para decidir")
                .setSubText("🌐 Acción requerida")
                .setContentIntent(mainPendingIntent)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("$appName está solicitando acceso a Internet.\n\n" +
                            "Toca para permitir o bloquear"))
                .setLargeIcon(appIcon)
                /*.addAction(
                    R.drawable.ic_lock,
                    "Bloquear",
                    blockPendingIntent
                )
                .addAction(
                    R.drawable.ic_check,
                    "Permitir",
                    allowPendingIntent
                )*/
                .setColor(context.getColor(R.color.firewall_green))
                .setColorized(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(longArrayOf(0, 100, 200, 100))
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setLights(context.getColor(R.color.firewall_green), 1000, 3000)
                .build()

            notificationManager.notify(packageName.hashCode(), notification)

        } catch (e: Exception) {
            // Si algo falla, usar formato simple
            val appIcon = getAppIconBitmap(packageName)
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle("$appName intenta acceder a internet")
                .setContentText("Toca para permitir o bloquear")
                .setLargeIcon(appIcon)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        packageName.hashCode(),
                        Intent(context, InterceptDialogActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            putExtra("package_name", packageName)
                            putExtra("app_name", appName)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            notificationManager.notify(packageName.hashCode(), notification)
        }
    }

    fun hasBeenNotifiedInSession(packageName: String): Boolean {
        return notifiedAppsInSession.contains(packageName)
    }

    fun removeNotified(packageName: String) {
        notifiedAppsInSession.remove(packageName)
    }

    fun clearSessionNotifications() {
        notifiedAppsInSession.clear()
    }
}
