package cu.maxwell.firenetstats.utils

import android.app.usage.NetworkStats
import android.app.usage.NetworkStats.Bucket
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

object NetworkUtils {

    enum class TimePeriod(val displayName: String) {
        SESSION("Esta Sesión"),
        TODAY("Hoy"),
        WEEKLY("Semanal"),
        MONTHLY("Mensual"),
        TOTAL("Total")
    }

    private var lastRxBytes: Long = 0
    private var lastTxBytes: Long = 0
    private var lastUpdateTime: Long = 0
    
    // Nuevas variables para seguimiento mensual
    private const val PREFS_NAME = "network_monthly_stats"
    private const val KEY_MONTH_START_RX = "month_start_rx"
    private const val KEY_MONTH_START_TX = "month_start_tx"
    private const val KEY_CURRENT_MONTH = "current_month"
    
    // Constantes para velocidades máximas
    private const val PREFS_MAX_SPEEDS = "network_max_speeds"
    private const val KEY_MAX_DOWNLOAD_SPEED = "max_download_speed"
    private const val KEY_MAX_UPLOAD_SPEED = "max_upload_speed"
    
    // Constantes para tracking desde que la app se abre
    private const val PREFS_TRACKING = "network_tracking_since_open"
    private const val KEY_TRACKING_ACTIVE = "tracking_active"
    private const val KEY_TRACK_START_TIME = "track_start_time"
    private const val KEY_TRACK_UID_LIST = "track_uids"
    private const val KEY_START_RX_PREFIX = "start_rx_"
    private const val KEY_START_TX_PREFIX = "start_tx_"
    
    data class NetworkStats(
        val downloadSpeed: String,
        val uploadSpeed: String,
        val downloadSpeedRaw: Float,
        val uploadSpeedRaw: Float,
        val unit: String,
        val downloadUnit: String,
        val uploadUnit: String
    )

    data class AppDataUsage(
        val packageName: String,
        val appName: String,
        val icon: Drawable?,
        val totalBytes: Long,
        val formattedUsage: String
    )
    
    // Función existente sin cambios
    fun getNetworkStats(context: Context): NetworkStats {
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentTime = System.currentTimeMillis()
        
        var downloadSpeedBps = 0.0
        var uploadSpeedBps = 0.0
        
        if (lastUpdateTime > 0) {
            val timeDifference = (currentTime - lastUpdateTime) / 1000.0
            
            if (timeDifference > 0) {
                downloadSpeedBps = (currentRxBytes - lastRxBytes) / timeDifference
                uploadSpeedBps = (currentTxBytes - lastTxBytes) / timeDifference
            }
        }
        
        lastRxBytes = currentRxBytes
        lastTxBytes = currentTxBytes
        lastUpdateTime = currentTime
        
        val downloadSpeedRawKBps = downloadSpeedBps.toFloat() / 1024
        val uploadSpeedRawKBps = uploadSpeedBps.toFloat() / 1024
        
        val (downloadSpeedFormatted, downloadUnitFormatted) = formatSpeedWithUnit(downloadSpeedBps)
        val (uploadSpeedFormatted, uploadUnitFormatted) = formatSpeedWithUnit(uploadSpeedBps)
        
        val commonUnit = if (downloadUnitFormatted == "MB/s" || uploadUnitFormatted == "MB/s") "MB/s" else "KB/s"
        
        return NetworkStats(
            downloadSpeed = downloadSpeedFormatted,
            uploadSpeed = uploadSpeedFormatted,
            downloadSpeedRaw = downloadSpeedRawKBps,
            uploadSpeedRaw = uploadSpeedRawKBps,
            unit = commonUnit,
            downloadUnit = downloadUnitFormatted,
            uploadUnit = uploadUnitFormatted
        )
    }
    
    private fun formatSpeedWithUnit(speedBps: Double): Pair<String, String> {
        val df = DecimalFormat("#.##")
        val dfMB = DecimalFormat("#.#")
        
        return when {
            speedBps < 1024 -> {
                Pair(df.format(speedBps), "B/s")
            }
            speedBps < 1024 * 1024 -> {
                val speedKBps = speedBps / 1024
                Pair(speedKBps.toInt().toString(), "KB/s")
            }
            else -> {
                val speedMBps = speedBps / (1024 * 1024)
                Pair(dfMB.format(speedMBps), "MB/s")
            }
        }
    }
    
    // NUEVA FUNCIÓN: Obtener uso de datos del mes actual
    fun getMonthlyDataUsage(context: Context): String {
        checkAndResetMonthlyStats(context)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val monthStartRx = prefs.getLong(KEY_MONTH_START_RX, 0L)
        val monthStartTx = prefs.getLong(KEY_MONTH_START_TX, 0L)
        
        try {
            val currentRxBytes = TrafficStats.getTotalRxBytes()
            val currentTxBytes = TrafficStats.getTotalTxBytes()
            
            // Validación: si TrafficStats retorna -1 (error) o valores inválidos
            if (currentRxBytes == 0L && currentTxBytes == 0L && (monthStartRx > 0 || monthStartTx > 0)) {
                // Sin conexión pero tenemos datos guardados, devolver lo que tenemos
                val monthlyRxBytes = maxOf(0L, monthStartRx - prefs.getLong("${KEY_MONTH_START_RX}_backup", 0L))
                val monthlyTxBytes = maxOf(0L, monthStartTx - prefs.getLong("${KEY_MONTH_START_TX}_backup", 0L))
                val totalMonthlyBytes = monthlyRxBytes + monthlyTxBytes
                return formatBytes(totalMonthlyBytes)
            }
            
            // Si es la primera vez o los valores son inválidos
            if (monthStartRx == 0L || monthStartTx == 0L) {
                initializeMonthlyStats(context)
                return "0 B"
            }
            
            // Validación: detectar reset o error en el sistema (valores disminuyeron)
            if (currentRxBytes < monthStartRx || currentTxBytes < monthStartTx) {
                // El sistema fue reseteado, ajustamos el punto de inicio
                prefs.edit()
                    .putLong(KEY_MONTH_START_RX, currentRxBytes)
                    .putLong(KEY_MONTH_START_TX, currentTxBytes)
                    .apply()
                return "0 B"
            }
            
            val monthlyRxBytes = currentRxBytes - monthStartRx
            val monthlyTxBytes = currentTxBytes - monthStartTx
            val totalMonthlyBytes = monthlyRxBytes + monthlyTxBytes
            
            // Guardar backup para uso sin conexión
            prefs.edit()
                .putLong("${KEY_MONTH_START_RX}_backup", monthStartRx)
                .putLong("${KEY_MONTH_START_TX}_backup", monthStartTx)
                .apply()
            
            return formatBytes(totalMonthlyBytes)
        } catch (e: Exception) {
            // En caso de error, devolver lo que se tiene guardado
            return "Error"
        }
    }
    
    // Función existente sin cambios (para compatibilidad)
    fun getDataUsage(context: Context): String {
        val totalRxBytes = TrafficStats.getTotalRxBytes()
        val totalTxBytes = TrafficStats.getTotalTxBytes()
        val totalBytes = totalRxBytes + totalTxBytes
        return formatBytes(totalBytes)
    }
    
    // Verificar si cambió el mes y reiniciar estadísticas
    private fun checkAndResetMonthlyStats(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentMonth = getCurrentMonth()
        val savedMonth = prefs.getString(KEY_CURRENT_MONTH, "")
        
        if (currentMonth != savedMonth) {
            // Cambió el mes, reiniciar estadísticas
            initializeMonthlyStats(context)
        }
    }
    
    // Inicializar estadísticas del mes
    private fun initializeMonthlyStats(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentMonth = getCurrentMonth()
        
        prefs.edit()
            .putLong(KEY_MONTH_START_RX, currentRxBytes)
            .putLong(KEY_MONTH_START_TX, currentTxBytes)
            .putString(KEY_CURRENT_MONTH, currentMonth)
            .apply()
    }
    
    // Obtener mes actual en formato "YYYY-MM"
    private fun getCurrentMonth(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        return dateFormat.format(Date())
    }
    
    // Función para reiniciar manualmente las estadísticas mensuales
    fun resetMonthlyStats(context: Context) {
        initializeMonthlyStats(context)
    }
    
    // Función existente sin cambios
    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        val df = DecimalFormat("#.##")
        return when {
            gb >= 1 -> "${df.format(gb)} GB"
            mb >= 1 -> "${df.format(mb)} MB"
            kb >= 1 -> "${df.format(kb)} KB"
            else -> "${bytes} B"
        }
    }
    
    // Funciones existentes sin cambios
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    
    fun isMobileDataConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }
    
    fun resetCounters() {
        lastRxBytes = 0
        lastTxBytes = 0
        lastUpdateTime = 0
    }

    // Inicia el tracking de consumo desde que la app se abre
    fun startTrackingSinceAppOpen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_TRACKING, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val startTime = System.currentTimeMillis()
        
        editor.putLong(KEY_TRACK_START_TIME, startTime)
        editor.putBoolean(KEY_TRACKING_ACTIVE, true)
        editor.apply()
    }

    // Detiene el tracking y limpia los valores guardados
    fun stopTrackingSinceAppOpen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_TRACKING, Context.MODE_PRIVATE)
        val uidCsv = prefs.getString(KEY_TRACK_UID_LIST, "") ?: ""
        val editor = prefs.edit()

        if (uidCsv.isNotEmpty()) {
            val parts = uidCsv.split(',')
            for (p in parts) {
                if (p.isBlank()) continue
                val keyRx = KEY_START_RX_PREFIX + p
                val keyTx = KEY_START_TX_PREFIX + p
                editor.remove(keyRx)
                editor.remove(keyTx)
            }
        }

        editor.remove(KEY_TRACK_UID_LIST)
        editor.remove(KEY_TRACK_START_TIME)
        editor.putBoolean(KEY_TRACKING_ACTIVE, false)
        editor.apply()
    }

    // Obtener consumo de datos por app desde que se inició el tracking (app abierta)
    fun getAppDataUsageSinceAppOpen(context: Context): List<AppDataUsage> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return emptyList()
        }

        val packageManager = context.packageManager
        val appUsageList = mutableListOf<AppDataUsage>()

        try {
            // Intentar con NetworkStatsManager primero
            val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            
            val startTime = 0L
            val endTime = System.currentTimeMillis()

            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val uid = appInfo.uid
                
                try {
                    val wifiStats = networkStatsManager.queryDetailsForUid(
                        ConnectivityManager.TYPE_WIFI,
                        null,
                        startTime,
                        endTime,
                        uid
                    )
                    
                    val mobileStats = networkStatsManager.queryDetailsForUid(
                        ConnectivityManager.TYPE_MOBILE,
                        null,
                        startTime,
                        endTime,
                        uid
                    )
                    
                    var totalRxBytes = 0L
                    var totalTxBytes = 0L
                    
                    val wifiBucket = Bucket()
                    while (wifiStats.hasNextBucket() && wifiStats.getNextBucket(wifiBucket)) {
                        totalRxBytes += wifiBucket.rxBytes
                        totalTxBytes += wifiBucket.txBytes
                    }
                    wifiStats.close()
                    
                    val mobileBucket = Bucket()
                    while (mobileStats.hasNextBucket() && mobileStats.getNextBucket(mobileBucket)) {
                        totalRxBytes += mobileBucket.rxBytes
                        totalTxBytes += mobileBucket.txBytes
                    }
                    mobileStats.close()
                    
                    val totalBytes = totalRxBytes + totalTxBytes
                    
                    if (totalBytes > 0) {
                        val appName = packageManager.getApplicationLabel(appInfo).toString()
                        val appIcon = try {
                            packageManager.getApplicationIcon(appInfo)
                        } catch (e: Exception) {
                            packageManager.defaultActivityIcon
                        }
                        val formattedUsage = formatBytes(totalBytes)
                        appUsageList.add(AppDataUsage(
                            packageName = pkg.packageName,
                            appName = appName,
                            icon = appIcon,
                            totalBytes = totalBytes,
                            formattedUsage = formattedUsage
                        ))
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            // Si NetworkStatsManager no devuelve datos, usar TrafficStats como fallback
            if (appUsageList.isEmpty()) {
                for (pkg in packages) {
                    val appInfo = pkg.applicationInfo ?: continue
                    val uid = appInfo.uid
                    
                    try {
                        val currentRxBytes = TrafficStats.getUidRxBytes(uid)
                        val currentTxBytes = TrafficStats.getUidTxBytes(uid)
                        
                        if (currentRxBytes != TrafficStats.UNSUPPORTED.toLong() && currentTxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                            val totalBytes = currentRxBytes + currentTxBytes
                            
                            if (totalBytes > 0) {
                                val appName = packageManager.getApplicationLabel(appInfo).toString()
                                val appIcon = try {
                                    packageManager.getApplicationIcon(appInfo)
                                } catch (e: Exception) {
                                    packageManager.defaultActivityIcon
                                }
                                val formattedUsage = formatBytes(totalBytes)
                                appUsageList.add(AppDataUsage(
                                    packageName = pkg.packageName,
                                    appName = appName,
                                    icon = appIcon,
                                    totalBytes = totalBytes,
                                    formattedUsage = formattedUsage
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return appUsageList.sortedByDescending { it.totalBytes }
    }

	fun getAppDataUsage(context: Context, period: TimePeriod): List<AppDataUsage> {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()

		val packageManager = context.packageManager
		val networkStatsManager =
			context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

		val (startTime, endTime) = getTimeRangeForPeriod(context, period)
		val appUsageList = mutableListOf<AppDataUsage>()

		val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)

		for (pkg in packages) {
			val appInfo = pkg.applicationInfo ?: continue
			val uid = appInfo.uid

			var totalBytes = 0L

			// 📶 WiFi
			try {
				val wifiStats = networkStatsManager.queryDetailsForUid(
					ConnectivityManager.TYPE_WIFI, null, startTime, endTime, uid
				)
				val bucket = Bucket()
				while (wifiStats.hasNextBucket()) {
					wifiStats.getNextBucket(bucket)
					totalBytes += bucket.rxBytes + bucket.txBytes
				}
				wifiStats.close()
			} catch (_: Exception) {}

			// 📡 Datos móviles
			try {
				val mobileStats = networkStatsManager.queryDetailsForUid(
					ConnectivityManager.TYPE_MOBILE, null, startTime, endTime, uid
				)
				val bucket = Bucket()
				while (mobileStats.hasNextBucket()) {
					mobileStats.getNextBucket(bucket)
					totalBytes += bucket.rxBytes + bucket.txBytes
				}
				mobileStats.close()
			} catch (_: Exception) {}

			// 🧠 Solo apps con consumo real
			if (totalBytes > 0) {
				val appName = packageManager.getApplicationLabel(appInfo).toString()
				val appIcon = try {
					packageManager.getApplicationIcon(appInfo)
				} catch (_: Exception) {
					packageManager.defaultActivityIcon
				}

				appUsageList.add(
					AppDataUsage(
						packageName = pkg.packageName,
						appName = appName,
						icon = appIcon,
						totalBytes = totalBytes,
						formattedUsage = formatBytes(totalBytes)
					)
				)
			}
		}

		return appUsageList.sortedByDescending { it.totalBytes }
	}

    // Función de compatibilidad hacia atrás
    fun getWeeklyAppDataUsage(context: Context): List<AppDataUsage> {
        return getAppDataUsage(context, TimePeriod.WEEKLY)
    }

    // Función para obtener el timestamp de inicio de sesión
    fun getSessionStartTime(context: Context): Long {
        val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
        var startTime = prefs.getLong("session_start_time", 0L)

        if (startTime == 0L) {
            // Primera vez que se abre la app en esta sesión
            startTime = System.currentTimeMillis()
            prefs.edit().putLong("session_start_time", startTime).apply()
        }

        return startTime
    }

    // Función para reiniciar el tiempo de sesión (útil para testing)
    fun resetSessionStartTime(context: Context) {
        val prefs = context.getSharedPreferences("session_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("session_start_time").apply()
    }

    // Función helper para calcular rango de tiempo según período
    private fun getTimeRangeForPeriod(context: Context, period: TimePeriod): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis

        val startTime = when (period) {
            TimePeriod.SESSION -> {
                // Desde que se abrió la app por primera vez en esta sesión
                getSessionStartTime(context)
            }
            TimePeriod.TODAY -> {
                // Inicio del día actual
                calendar.apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            TimePeriod.WEEKLY -> {
                // Hace 7 días
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                calendar.timeInMillis
            }
            TimePeriod.MONTHLY -> {
                // Hace 30 días
                calendar.add(Calendar.DAY_OF_YEAR, -30)
                calendar.timeInMillis
            }
            TimePeriod.TOTAL -> {
                // Desde el inicio de los tiempos (o un valor muy antiguo)
                0L
            }
        }

        return Pair(startTime, endTime)
    }

    fun getLocalIpAddress(context: Context): String {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return "N/A"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "N/A"
            
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                // Para WiFi, usar WifiManager
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val connectionInfo = wifiManager.connectionInfo
                val ipAddress = connectionInfo?.ipAddress ?: 0
                
                if (ipAddress != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        (ipAddress and 0xff),
                        ((ipAddress shr 8) and 0xff),
                        ((ipAddress shr 16) and 0xff),
                        ((ipAddress shr 24) and 0xff)
                    )
                } else {
                    return "0.0.0.0"
                }
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                // Para redes celulares, usar Socket para conectarse a DNS externo
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 1500)
                    val ipAddress = socket.localAddress.hostAddress ?: "0.0.0.0"
                    socket.close()
                    return ipAddress
                } catch (e: Exception) {
                    // Fallback: intentar obtener desde interfaces de red
                    return getIpFromNetworkInterface()
                }
            }
            
            return "0.0.0.0"
        } catch (e: Exception) {
            return "Error"
        }
    }

    private fun getIpFromNetworkInterface(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.contains("lo")) continue // Saltar loopback
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            return "0.0.0.0"
        } catch (e: Exception) {
            return "0.0.0.0"
        }
    }

    // Funciones para rastrear y obtener velocidades máximas
    fun updateMaxSpeeds(context: Context, downloadSpeedBps: Double, uploadSpeedBps: Double) {
        val prefs = context.getSharedPreferences(PREFS_MAX_SPEEDS, Context.MODE_PRIVATE)
        val maxDownload = prefs.getFloat(KEY_MAX_DOWNLOAD_SPEED, 0f)
        val maxUpload = prefs.getFloat(KEY_MAX_UPLOAD_SPEED, 0f)
        
        var updated = false
        
        if (downloadSpeedBps > maxDownload) {
            prefs.edit().putFloat(KEY_MAX_DOWNLOAD_SPEED, downloadSpeedBps.toFloat()).apply()
            updated = true
        }
        
        if (uploadSpeedBps > maxUpload) {
            prefs.edit().putFloat(KEY_MAX_UPLOAD_SPEED, uploadSpeedBps.toFloat()).apply()
            updated = true
        }
    }
    
    fun getMaxDownloadSpeed(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_MAX_SPEEDS, Context.MODE_PRIVATE)
        val maxSpeedBps = prefs.getFloat(KEY_MAX_DOWNLOAD_SPEED, 0f).toDouble()
        return formatSpeedWithUnitForMax(maxSpeedBps)
    }
    
    fun getMaxUploadSpeed(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_MAX_SPEEDS, Context.MODE_PRIVATE)
        val maxSpeedBps = prefs.getFloat(KEY_MAX_UPLOAD_SPEED, 0f).toDouble()
        return formatSpeedWithUnitForMax(maxSpeedBps)
    }
    
    fun resetMaxSpeeds(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_MAX_SPEEDS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_MAX_DOWNLOAD_SPEED)
            .remove(KEY_MAX_UPLOAD_SPEED)
            .apply()
    }
    
    private fun formatSpeedWithUnitForMax(speedBps: Double): String {
        val df = DecimalFormat("#.#")
        val dfMB = DecimalFormat("#.##")
        
        return when {
            speedBps < 1024 -> {
                "${speedBps.toInt()} B/s"
            }
            speedBps < 1024 * 1024 -> {
                val speedKBps = speedBps / 1024
                "${df.format(speedKBps)} KB/s"
            }
            else -> {
                val speedMBps = speedBps / (1024 * 1024)
                "${dfMB.format(speedMBps)} MB/s"
            }
        }
    }

    // Verificar si el permiso PACKAGE_USAGE_STATS está otorgado
    fun hasPackageUsageStatsPermission(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = appOpsManager.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } else {
                // En versiones anteriores a M, no hay restricción para este permiso
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // Función para obtener datos de consumo de una app específica por período
    fun getAppDataUsageForUid(context: Context, uid: Int, period: TimePeriod): Triple<Long, Long, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Triple(0L, 0L, 0L)

        try {
            val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val (startTime, endTime) = getTimeRangeForPeriod(context, period)

            var downloadBytes = 0L
            var uploadBytes = 0L

            // WiFi
            try {
                val wifiStats = networkStatsManager.queryDetailsForUid(
                    ConnectivityManager.TYPE_WIFI, null, startTime, endTime, uid
                )
                val bucket = Bucket()
                while (wifiStats.hasNextBucket()) {
                    wifiStats.getNextBucket(bucket)
                    downloadBytes += bucket.rxBytes
                    uploadBytes += bucket.txBytes
                }
                wifiStats.close()
            } catch (_: Exception) {}

            // Datos móviles
            try {
                val mobileStats = networkStatsManager.queryDetailsForUid(
                    ConnectivityManager.TYPE_MOBILE, null, startTime, endTime, uid
                )
                val bucket = Bucket()
                while (mobileStats.hasNextBucket()) {
                    mobileStats.getNextBucket(bucket)
                    downloadBytes += bucket.rxBytes
                    uploadBytes += bucket.txBytes
                }
                mobileStats.close()
            } catch (_: Exception) {}

            val totalBytes = downloadBytes + uploadBytes
            return Triple(downloadBytes, uploadBytes, totalBytes)
        } catch (_: Exception) {
            return Triple(0L, 0L, 0L)
        }
    }
}

