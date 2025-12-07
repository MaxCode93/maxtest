package cu.maxwell.firenetstats

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.data.Entry
import cu.maxwell.firenetstats.databinding.FragmentHomeBinding
import cu.maxwell.firenetstats.utils.NetworkUtils
import cu.maxwell.firenetstats.utils.UpdateChecker
import cu.maxwell.firenetstats.utils.UpdateState
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val downloadSpeedEntries = ArrayList<Entry>()
    private val uploadSpeedEntries = ArrayList<Entry>()
    private var entryCount = 0
    private var timer: Timer? = null
    private lateinit var updateChecker: UpdateChecker
    private lateinit var speedometerDownload: SpeedWaveView
    private lateinit var speedometerUpload: SpeedWaveView
    private var maxDownloadSpeed = 0f
    private var maxUploadSpeed = 0f

    private lateinit var tileDownload: SpeedTileView
    private lateinit var tileUpload: SpeedTileView
    private var updateAppListCounter = 0  // Contador para actualizar apps cada 5 segundos
    private var searchText = ""  // Texto de búsqueda actual
    private lateinit var selectedTimePeriod: NetworkUtils.TimePeriod  // Período seleccionado, cargado de SharedPreferences
    private lateinit var appDataUsageAdapter: AppDataUsageAdapter
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var isFirstLoad = true  // Para mostrar loading solo en la primera carga

    // BroadcastReceiver para escuchar cambios en el estado del widget flotante
    private val widgetStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cu.maxwell.firenetstats.SERVICE_STATE_CHANGED") {
                updateWidgetFABState()
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PHONE_STATE_PERMISSION_REQUEST_CODE = 1002
        private const val ALL_PERMISSIONS_REQUEST_CODE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Inicializar update checker
        updateChecker = UpdateChecker(requireContext())

        // Verificar permisos
        checkAndRequestAllPermissions()

        // Verificar permiso PACKAGE_USAGE_STATS
        checkPackageUsageStatsPermission()

        // Iniciar rastreo de consumo de datos desde que la app se abre
        NetworkUtils.startTrackingSinceAppOpen(requireContext())

        setupSpeedTiles()
        setupTopApps() // Nueva sección de apps
        setupDataUsageListeners() // Configurar listeners para reset de datos

        updateNetworkInfo()
        updateWidgetFABState() // Actualizar estado del FAB

        // Verificar si el tooltip debe mostrarse
        checkAndShowTooltipIfNeeded()

        // Iniciar la actualización periódica de datos
        startDataUpdates()

        // Verificar actualizaciones disponibles
        checkForUpdates()
		
    }

    override fun onPause() {
        super.onPause()
        // Desregistrar receiver cuando el fragment se pausa
        try {
            requireContext().unregisterReceiver(widgetStateReceiver)
        } catch (e: Exception) {
            // Receiver ya desregistrado o contexto no disponible
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timer?.cancel()
        _binding = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        // Registrar receiver para cambios en el estado del widget
        val filter = IntentFilter("cu.maxwell.firenetstats.SERVICE_STATE_CHANGED")
        requireContext().registerReceiver(widgetStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Actualizar estado del FAB cada vez que el fragment se muestra
        updateWidgetFABState()
        // Actualizar el estado de la UI de permisos de datos
        updatePermissionUI()
        // Recargar los datos de aplicaciones si el permiso ahora está disponible
        updateTopApps()
    }

    private fun checkAndRequestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Verificar permiso de ubicación
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            // Verificar permiso de estado del teléfono
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
            }
        }

        // Si hay permisos que solicitar, hacerlo
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsToRequest.toTypedArray(),
                ALL_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    // Verificar permiso especial PACKAGE_USAGE_STATS
    private fun checkPackageUsageStatsPermission() {
        // Obtener la preferencia guardada sobre si ya se mostró el diálogo
        val sharedPref = requireContext().getSharedPreferences("fireNetStats_prefs", Context.MODE_PRIVATE)
        val permissionDialogShown = sharedPref.getBoolean("usage_stats_dialog_shown", false)

        // Solo mostrar si: no se ha mostrado NUNCA y el permiso no está otorgado
        if (!permissionDialogShown && !NetworkUtils.hasPackageUsageStatsPermission(requireContext())) {
            // Marcar que ya se mostró el diálogo
            sharedPref.edit().putBoolean("usage_stats_dialog_shown", true).apply()

            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ Permiso Requerido")
                .setMessage("Se necesita acceso a 'Uso de datos' para mostrar el consumo de otras aplicaciones.\n\nPor favor, otorga este permiso en Configuración.")
                .setPositiveButton("Ir a Configuración") { _, _ ->
                    openUsageStatsSettings()
                }
                .setCancelable(false)
                .show()
        }
    }

    // Abrir configuración de permisos de uso de datos
    private fun openUsageStatsSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Si falla, abrir configuración general
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "No se pudo abrir la configuración", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // Permiso concedido, actualizar información
                    updateNetworkInfo()
                } else {
                    // Permiso denegado, mostrar mensaje
                    Toast.makeText(
                        requireContext(),
                        "Se requieren permisos de ubicación para mostrar el nombre de la red WiFi",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            PHONE_STATE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // Permiso concedido, actualizar información
                    updateNetworkInfo()
                } else {
                    // Permiso denegado, mostrar mensaje
                    Toast.makeText(
                        requireContext(),
                        "Se requiere permiso para acceder a información detallada de la red móvil",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            ALL_PERMISSIONS_REQUEST_CODE -> {
                // Procesar cada permiso individualmente
                var allGranted = true

                for (i in permissions.indices) {
                    if (grantResults[i] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        allGranted = false

                        // Mostrar mensaje específico según el permiso denegado
                        when (permissions[i]) {
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION -> {
                                Toast.makeText(
                                    requireContext(),
                                    "Se requieren permisos de ubicación para mostrar el nombre de la red WiFi",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            Manifest.permission.READ_PHONE_STATE -> {
                                Toast.makeText(
                                    requireContext(),
                                    "Se requiere permiso para acceder a información detallada de la red móvil",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }

                // Si todos los permisos fueron concedidos, actualizar la información
                if (allGranted) {
                    updateNetworkInfo()
                }
            }
        }
    }

    private fun updateNetworkInfo() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)

        if (networkCapabilities != null) {
            // Tipo de red
            when {
                networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                    binding.tvNetworkType.text = "WiFi"
                    binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_wifi), null, null, null)
                    updateWifiInfo()
                }
                networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    binding.tvNetworkType.text = "Datos Móviles"
                    binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_data), null, null, null)

                    // Intentar obtener información del operador móvil
                    val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                    val operatorName = telephonyManager?.networkOperatorName

                    if (!operatorName.isNullOrEmpty()) {
                        binding.tvWifiName.text = operatorName
                    } else {
                        binding.tvWifiName.text = "Desconocido"
                    }

                    // Actualizar icono de nombre de WiFi
                    binding.tvWifiNameLabel.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_wifi_name), null, null, null)

                    // Actualizar icono de dirección IP
                    binding.tvIPAddressLabel.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_ip_address), null, null, null)

                    // Actualizar dirección IP
                    binding.tvIPAddress.text = NetworkUtils.getLocalIpAddress(requireContext())

                    // Verificar permisos para información detallada
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        requireContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        binding.tvSignalStrength.text = "Sin permiso"
                        binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.poor_connection))
                        binding.tvSignalStrength.setOnClickListener {
                            ActivityCompat.requestPermissions(
                                requireActivity(),
                                arrayOf(Manifest.permission.READ_PHONE_STATE),
                                PHONE_STATE_PERMISSION_REQUEST_CODE
                            )
                        }
                    } else {
                        // Obtener la intensidad de la señal móvil
                        updateMobileSignalStrength()
                    }
                }
                else -> {
                    binding.tvNetworkType.text = getString(R.string.not_connected)
                    binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_no_network), null, null, null)
                    binding.tvWifiName.text = "N/A"
                    binding.tvSignalStrength.text = "N/A"
                    binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))

                    // También actualizar IP en caso de otra red
                    binding.tvIPAddress.text = NetworkUtils.getLocalIpAddress(requireContext())
                }
            }
        } else {
            binding.tvNetworkType.text = getString(R.string.not_connected)
            binding.tvNetworkTypeLabel.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_no_network), null, null, null)
            binding.tvWifiName.text = "N/A"
            binding.tvSignalStrength.text = "N/A"
            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            binding.tvIPAddress.text = "N/A"
        }

        // Uso de datos (calculado por NetworkUtils)
        val dataUsage = NetworkUtils.getMonthlyDataUsage(requireContext())
        binding.tvDataUsage.text = dataUsage
    }

    private fun updateMobileSignalStrength() {
        try {
            val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Para Android 10 (API 29) y superior
                val signalStrength = telephonyManager.signalStrength
                if (signalStrength != null) {
                    // Obtener el nivel de señal para la red celular (0-4)
                    val level = signalStrength.level

                    // Mostrar valor numérico de la señal (0-4)
                    binding.tvSignalStrengthValue.text = "($level/4)"

                    when (level) {
                        0 -> { // SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                            binding.tvSignalStrength.text = "Desconocida"
                            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                            binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_unknown), null, null, null)
                        }
                        1 -> { // SIGNAL_STRENGTH_POOR
                            binding.tvSignalStrength.text = "Débil"
                            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.poor_connection))
                            binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_weak), null, null, null)
                        }
                        2 -> { // SIGNAL_STRENGTH_MODERATE
                            binding.tvSignalStrength.text = "Moderada"
                            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.medium_connection))
                            binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_medium), null, null, null)
                        }
                        3, 4 -> { // SIGNAL_STRENGTH_GOOD, SIGNAL_STRENGTH_GREAT
                            binding.tvSignalStrength.text = "Excelente"
                            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.good_connection))
                            binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_strong), null, null, null)
                        }
                        else -> {
                            binding.tvSignalStrength.text = "N/A"
                            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                            binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_unknown), null, null, null)
                        }
                    }
                } else {
                    binding.tvSignalStrength.text = "N/A"
                    binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_unknown), null, null, null)
                    binding.tvSignalStrengthValue.text = "(0/4)"
                }
            } else {
                @Suppress("DEPRECATION")
                val phoneStateListener = object : android.telephony.PhoneStateListener() {
                    @Suppress("DEPRECATION")
                    override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength?) {
                        super.onSignalStrengthsChanged(signalStrength)

                        if (signalStrength != null) {
                            val gsmSignalStrength = try {
                                val method = android.telephony.SignalStrength::class.java.getDeclaredMethod("getGsmSignalStrength")
                                method.isAccessible = true
                                method.invoke(signalStrength) as Int
                            } catch (e: Exception) {
                                try {
                                    val method = android.telephony.SignalStrength::class.java.getDeclaredMethod("getLevel")
                                    method.isAccessible = true
                                    method.invoke(signalStrength) as Int
                                } catch (e2: Exception) {
                                    -1
                                }
                            }

                            requireActivity().runOnUiThread {
                                // Mostrar valor numérico de la señal (0-99)
                                binding.tvSignalStrengthValue.text = "($gsmSignalStrength/99)"

                                when {
                                    gsmSignalStrength <= 0 || gsmSignalStrength >= 99 -> {
                                        binding.tvSignalStrength.text =
                                            getString(R.string.desconocida)
                                        binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                                        binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_unknown), null, null, null)
                                    }
                                    gsmSignalStrength < 10 -> {
                                        binding.tvSignalStrength.text = getString(R.string.d_bil)
                                        binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.poor_connection))
                                        binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_weak), null, null, null)
                                    }
                                    gsmSignalStrength < 20 -> {
                                        binding.tvSignalStrength.text = getString(R.string.moderada)
                                        binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.medium_connection))
                                        binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_medium), null, null, null)
                                    }
                                    else -> {
                                        binding.tvSignalStrength.text =
                                            getString(R.string.excelente)
                                        binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.good_connection))
                                        binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_strong), null, null, null)
                                    }
                                }
                            }
                        }

                        // Dejar de escuchar después de obtener la información
                        @Suppress("DEPRECATION")
                        telephonyManager.listen(this, android.telephony.PhoneStateListener.LISTEN_NONE)
                    }
                }

                // Comenzar a escuchar cambios en la intensidad de la señal
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)

                // Establecer un valor predeterminado mientras se obtiene la información
                binding.tvSignalStrength.text = getString(R.string.obteniendo)
                binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                binding.tvSignalStrengthValue.text = "(0/99)"
            }
        } catch (e: Exception) {
            // En caso de error, mostrar N/A
            binding.tvSignalStrength.text = "N/A"
            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            binding.tvSignalStrengthLabel.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_mobile_signal_unknown), null, null, null)
            binding.tvSignalStrengthValue.text = "(0/99)"
        }
    }

    private fun updateWifiInfo() {
        val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = wifiManager.connectionInfo

        // Verificar permisos de ubicación
        val hasLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // En versiones anteriores, no se requiere permiso
        }

        if (hasLocationPermission) {
            val ssid = wifiInfo.ssid?.replace("\"", "") ?: "Desconocido"
            binding.tvWifiName.text = ssid

            // Obtener y mostrar intensidad de señal WiFi
            val rssi = wifiInfo.rssi
            if (rssi != 0) { // 0 puede indicar que no hay conexión
                @Suppress("DEPRECATION")
                val signalPercentage = android.net.wifi.WifiManager.calculateSignalLevel(rssi, 100)
                binding.tvSignalStrength.text = when {
                    signalPercentage >= 80 -> "Excelente"
                    signalPercentage >= 60 -> "Buena"
                    signalPercentage >= 40 -> "Moderada"
                    signalPercentage >= 20 -> "Débil"
                    else -> "Muy débil"
                }

                // Mostrar valor numérico de la señal
                binding.tvSignalStrengthValue.text = "($signalPercentage%)"

                // Actualizar color según la intensidad
                binding.tvSignalStrength.setTextColor(
                    when {
                        signalPercentage >= 80 -> ContextCompat.getColor(requireContext(), R.color.good_connection)
                        signalPercentage >= 60 -> ContextCompat.getColor(requireContext(), R.color.medium_connection)
                        signalPercentage >= 40 -> ContextCompat.getColor(requireContext(), R.color.medium_connection)
                        else -> ContextCompat.getColor(requireContext(), R.color.poor_connection)
                    }
                )
            } else {
                binding.tvSignalStrength.text = "N/A"
                binding.tvSignalStrengthValue.text = "(0%)"
                binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            }
        } else {
            binding.tvWifiName.text = getString(R.string.sin_permiso_de_ubicacion)
            binding.tvWifiName.setOnClickListener {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
            // Si no hay permiso de ubicación, no podemos obtener la intensidad de la señal WiFi
            binding.tvSignalStrength.text = "N/A"
            binding.tvSignalStrengthValue.text = "(0%)"
            binding.tvSignalStrength.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        }

        // Actualizar icono de nombre de WiFi
        binding.tvWifiNameLabel.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_wifi_name), null, null, null)

        // Actualizar icono de dirección IP
        binding.tvIPAddressLabel.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_ip_address), null, null, null)

        // Actualizar dirección IP
        binding.tvIPAddress.text = NetworkUtils.getLocalIpAddress(requireContext())

        // Actualizar icono de uso de datos
        binding.tvDataUsageLabel.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_data_usage), null, null, null)
    }

    private fun startDataUpdates() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                if (!isAdded) return

                val safeContext = context ?: return
                val networkStats = NetworkUtils.getNetworkStats(safeContext)
                val activity = activity ?: return

                activity.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    val uiContext = context ?: return@runOnUiThread

                    val downloadSpeedBps = networkStats.downloadSpeedRaw * 1024f // KB/s a B/s
                    val uploadSpeedBps = networkStats.uploadSpeedRaw * 1024f   // KB/s a B/s

                    if (downloadSpeedBps > maxDownloadSpeed) {
                        maxDownloadSpeed = downloadSpeedBps
                    }
                    if (uploadSpeedBps > maxUploadSpeed) {
                        maxUploadSpeed = uploadSpeedBps
                    }

                    NetworkUtils.updateMaxSpeeds(uiContext, downloadSpeedBps.toDouble(), uploadSpeedBps.toDouble())
                    tileDownload.setMaxSpeed(NetworkUtils.getMaxDownloadSpeed(uiContext))
                    tileUpload.setMaxSpeed(NetworkUtils.getMaxUploadSpeed(uiContext))

                    tileDownload.updateSpeed(downloadSpeedBps)
                    tileUpload.updateSpeed(uploadSpeedBps)

                    updateChart(networkStats.downloadSpeedRaw, networkStats.uploadSpeedRaw)

                    updateNetworkInfo()

                    updateAppListCounter++
                    if (updateAppListCounter >= 5) {
                        updateTopApps()
                        updateAppListCounter = 0
                    }
                }
            }
        }, 0, 1000)
    }

    private fun setupSpeedTiles() {
        tileDownload = binding.tileDownload
        tileUpload = binding.tileUpload

        tileDownload.setMeterType(SpeedWaveView.MeterType.DOWNLOAD)
        tileUpload.setMeterType(SpeedWaveView.MeterType.UPLOAD)

        // Cargar y mostrar máximos guardados
        tileDownload.setMaxSpeed(NetworkUtils.getMaxDownloadSpeed(requireContext()))
        tileUpload.setMaxSpeed(NetworkUtils.getMaxUploadSpeed(requireContext()))
    }

    private fun updateChart(downloadSpeed: Float, uploadSpeed: Float) {
        if (downloadSpeedEntries.size >= 30) {
            downloadSpeedEntries.removeAt(0)
            uploadSpeedEntries.removeAt(0)

            // Actualizar índices para mantener consistencia
            for (i in 0 until downloadSpeedEntries.size) {
                downloadSpeedEntries[i] = Entry(i.toFloat(), downloadSpeedEntries[i].y)
                uploadSpeedEntries[i] = Entry(i.toFloat(), uploadSpeedEntries[i].y)
            }
        }

        downloadSpeedEntries.add(Entry(downloadSpeedEntries.size.toFloat(), downloadSpeed))
        uploadSpeedEntries.add(Entry(uploadSpeedEntries.size.toFloat(), uploadSpeed))
    }

    private fun setupTopApps() {
        val recyclerViewApps = binding.recyclerViewApps
        appDataUsageAdapter = AppDataUsageAdapter()
        recyclerViewApps.adapter = appDataUsageAdapter
        recyclerViewApps.layoutManager = LinearLayoutManager(requireContext())
        setupTimePeriodChips()
        setupConsumptionInfo()

        // Configurar botón de otorgar permiso
        val btnGrantPermission = binding.btnGrantPermission
        btnGrantPermission.setOnClickListener {
            openUsageStatsSettings()
        }

        // Configurar FAB de toggle del widget flotante
        val fabToggleWidget = binding.fabToggleWidget
        fabToggleWidget.setOnClickListener {
            toggleFloatingWidget()
            // Ocultar tooltip después del primer click
            hideTooltip()
        }

        // Configurar animación flotante del tooltip
        setupTooltipFloatingAnimation()

        updatePermissionUI()
    }

    private fun setupTimePeriodChips() {
        val chipGroup = binding.chipGroupTimePeriod

        // Cargar período seleccionado de SharedPreferences
        val sharedPref = requireContext().getSharedPreferences("fireNetStats_prefs", Context.MODE_PRIVATE)
        selectedTimePeriod = try {
            val savedPeriod = sharedPref.getString("selected_time_period", NetworkUtils.TimePeriod.MONTHLY.name)
            NetworkUtils.TimePeriod.valueOf(savedPeriod ?: NetworkUtils.TimePeriod.MONTHLY.name)
        } catch (e: Exception) {
            NetworkUtils.TimePeriod.MONTHLY // Default si hay error
        }

        // Configurar listener para cambios de selección
        @Suppress("DEPRECATION")
        chipGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedTimePeriod = when (checkedId) {
                R.id.chipToday -> NetworkUtils.TimePeriod.TODAY
                R.id.chipWeekly -> NetworkUtils.TimePeriod.WEEKLY
                R.id.chipMonthly -> NetworkUtils.TimePeriod.MONTHLY
                R.id.chipTotal -> NetworkUtils.TimePeriod.TOTAL
                else -> NetworkUtils.TimePeriod.MONTHLY // Default
            }
            // Guardar en SharedPreferences
            sharedPref.edit().putString("selected_time_period", selectedTimePeriod.name).apply()
            // Forzar actualización al cambiar período
            updateTopApps()
        }

        // Seleccionar el chip correspondiente al período cargado
        val chipIdToCheck = when (selectedTimePeriod) {
            NetworkUtils.TimePeriod.TODAY -> R.id.chipToday
            NetworkUtils.TimePeriod.WEEKLY -> R.id.chipWeekly
            NetworkUtils.TimePeriod.MONTHLY -> R.id.chipMonthly
            NetworkUtils.TimePeriod.TOTAL -> R.id.chipTotal
            else -> R.id.chipMonthly // Default
        }
        chipGroup.check(chipIdToCheck)
    }

    private fun setupConsumptionInfo() {
        val tvConsumptionTitle = binding.tvConsumptionTitle
        tvConsumptionTitle.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "Estos datos son obtenidos del sistema, pueden no ser fieles al consumo real",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updatePermissionUI() {
        val hasPermission = NetworkUtils.hasPackageUsageStatsPermission(requireContext())
        val permissionLayout = binding.permissionRequiredLayout
        val recyclerViewApps = binding.recyclerViewApps

        if (hasPermission) {
            permissionLayout.visibility = View.GONE
            recyclerViewApps.visibility = View.VISIBLE
            updateTopApps()
        } else {
            permissionLayout.visibility = View.VISIBLE
            recyclerViewApps.visibility = View.GONE
        }
    }

    private fun setupDataUsageListeners() {
        // Función auxiliar para mostrar diálogo de confirmación de reset
        val showResetConfirmation = {
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ Resetear Contador")
                .setMessage("Se iniciará un nuevo contador de uso de datos desde este momento.\n\n¿Deseas continuar?")
                .setPositiveButton("Resetear") { _, _ ->
                    // Resetear los datos
                    NetworkUtils.resetMonthlyStats(requireContext())

                    // Actualizar UI inmediatamente
                    binding.tvDataUsage.text = "0 B"

                    // Forzar actualización del resto de la UI
                    updateNetworkInfo()

                    // Mostrar confirmación
                    Toast.makeText(requireContext(), "✓ Contador reseteado correctamente", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // Listener para resetear uso de datos al hacer click en el texto
        binding.tvDataUsage.setOnClickListener {
            if (isAdded) {
                showResetConfirmation.invoke()
            }
        }

        // Listener para resetear uso de datos al hacer click en el botón
        binding.btnResetDataUsage.setOnClickListener {
            if (isAdded) {
                showResetConfirmation.invoke()
            }
        }

        // Habilitar el botón de reset por defecto
        binding.btnResetDataUsage.isEnabled = true
        binding.btnResetDataUsage.alpha = 1.0f
    }

    private fun updateTopApps() {
        // Verificar si tenemos permiso antes de intentar obtener datos
        if (!NetworkUtils.hasPackageUsageStatsPermission(requireContext())) {
            return
        }

        // Mostrar indicador de carga solo en la primera carga
        val showLoading = isFirstLoad
        if (showLoading) {
            binding.loadingLayout.visibility = View.VISIBLE
            binding.recyclerViewApps.visibility = View.GONE
        }

        // Cargar datos en background thread para no bloquear UI
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Cargar apps del sistema en background
                val allApps = NetworkUtils.getAppDataUsage(requireContext(), selectedTimePeriod)

                // Filtrar por búsqueda
                val filteredApps = if (searchText.isEmpty()) {
                    allApps
                } else {
                    allApps.filter { app ->
                        app.appName.contains(searchText, ignoreCase = true) ||
                        app.packageName.contains(searchText, ignoreCase = true)
                    }
                }

                // Actualizar el adapter en el hilo principal
                withContext(Dispatchers.Main) {
                    appDataUsageAdapter.updateData(filteredApps)

                    // Ocultar indicador de carga y mostrar RecyclerView solo si se mostró
                    if (showLoading) {
                        binding.loadingLayout.visibility = View.GONE
                        binding.recyclerViewApps.visibility = View.VISIBLE
                        isFirstLoad = false  // Marcar que ya no es la primera carga
                    }
                }
            } catch (e: Exception) {
                // En caso de error, ocultar loading y mostrar mensaje
                withContext(Dispatchers.Main) {
                    if (showLoading) {
                        binding.loadingLayout.visibility = View.GONE
                        binding.recyclerViewApps.visibility = View.VISIBLE
                        isFirstLoad = false  // Marcar que ya no es la primera carga
                    }
                    Toast.makeText(requireContext(), "Error al cargar datos de aplicaciones", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ======================== UPDATE SYSTEM ========================

    private fun checkForUpdates() {
        updateChecker.checkForUpdates(object : UpdateChecker.UpdateCheckListener {
            override fun onUpdateAvailable(updateState: UpdateState) {
                activity?.runOnUiThread {
                    // showUpdateIcon(true)
                }
            }

            override fun onNoUpdate() {
                activity?.runOnUiThread {
                    // showUpdateIcon(false)
                }
            }

            override fun onCheckError(error: String) {
                // Silenciar errores al usuario, pero ocultar icono si falla
                activity?.runOnUiThread {
                    // showUpdateIcon(false)
                }
            }
        }, forceCheck = true)
    }

    private fun startFloatingWidget() {
        val context = requireContext()
        
        // Verificar si tenemos el permiso SYSTEM_ALERT_WINDOW
        if (!hasOverlayPermission(context)) {
            // Mostrar diálogo pidiendo permiso
            showPermissionDialog()
            return
        }
        
        // Iniciar el widget
        val intent = Intent(context, FloatingWidgetService::class.java)
        context.startService(intent)
        
        // Actualizar el FAB inmediatamente
        updateWidgetFABState()
    }

    private fun stopFloatingWidget() {
        val context = requireContext()
        
        // Detener el widget
        val intent = Intent(context, FloatingWidgetService::class.java)
        context.stopService(intent)
        
        // Esperar un poco para que el servicio se detenga completamente antes de actualizar UI
        binding.fabToggleWidget.postDelayed({
            updateWidgetFABState()
        }, 200)
    }

    private fun toggleFloatingWidget() {
        val context = requireContext()
        
        // Verificar si tenemos el permiso SYSTEM_ALERT_WINDOW
        if (!hasOverlayPermission(context)) {
            // Mostrar diálogo pidiendo permiso
            showPermissionDialog()
            return
        }
        
        val isActuallyRunning = FloatingWidgetService.getActualServiceState(context)
        if (isActuallyRunning) {
            stopFloatingWidget()
        } else {
            startFloatingWidget()
        }
    }

    private fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun showPermissionDialog() {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle("Permiso Requerido")
            .setMessage("Se requiere el permiso para mostrar ventanas superpuestas para poder usar el widget flotante. Por favor, conceda el permiso en la configuración de la aplicación.")
            .setPositiveButton("Ir a Configuración") { _, _ ->
                // Abrir la configuración de la aplicación
                openAppSettings()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + requireContext().packageName)
        )
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Si no funciona el intent directo, abrir la configuración general
            val fallbackIntent = Intent(
                Settings.ACTION_APPLICATION_SETTINGS
            ).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(
                    requireContext(),
                    "No se pudo abrir la configuración",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateWidgetFABState() {
        val isActuallyRunning = FloatingWidgetService.getActualServiceState(requireContext())
        if (isActuallyRunning) {
            binding.fabToggleWidget.setImageResource(R.drawable.ic_disable_floating_widget)
            binding.fabToggleWidget.contentDescription = getString(R.string.disable_floating_widget)
            binding.tvTooltipText.text = "Desactivar widget"
        } else {
            binding.fabToggleWidget.setImageResource(R.drawable.ic_enable_floating_widget)
            binding.fabToggleWidget.contentDescription = getString(R.string.enable_floating_widget)
            binding.tvTooltipText.text = "Activar widget"
        }
    }

    private fun setupTooltipFloatingAnimation() {
        val tooltipCard = binding.tooltipCard
        
        // Crear animación de escala y translación Y
        val scaleY = android.animation.ValueAnimator.ofFloat(1f, 0.95f, 1f)
        scaleY.duration = 1500
        scaleY.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY.repeatMode = android.animation.ValueAnimator.RESTART
        scaleY.addUpdateListener { animator ->
            val value = animator.animatedValue as Float
            tooltipCard.scaleY = value
        }
        
        // Crear animación de traslación vertical
        val translationY = android.animation.ValueAnimator.ofFloat(0f, -10f, 0f)
        translationY.duration = 1500
        translationY.repeatCount = android.animation.ValueAnimator.INFINITE
        translationY.repeatMode = android.animation.ValueAnimator.RESTART
        translationY.addUpdateListener { animator ->
            val value = animator.animatedValue as Float
            tooltipCard.translationY = value
        }
        
        // Iniciar ambas animaciones
        scaleY.start()
        translationY.start()
    }

    private fun hideTooltip() {
        val tooltipCard = binding.tooltipCard
        
        // Animar desaparición del tooltip
        tooltipCard.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                tooltipCard.visibility = View.GONE
                
                // Guardar estado para que no reaparezca
                val prefs = requireContext().getSharedPreferences("widget_tooltip", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("tooltip_hidden", true).apply()
            }
            .start()
    }

    private fun checkAndShowTooltipIfNeeded() {
        val tooltipCard = binding.tooltipCard
        val prefs = requireContext().getSharedPreferences("widget_tooltip", Context.MODE_PRIVATE)
        val isTooltipHidden = prefs.getBoolean("tooltip_hidden", false)
        
        if (isTooltipHidden) {
            tooltipCard.visibility = View.GONE
            tooltipCard.alpha = 0f
        }
    }
}