package cu.maxwell.firenetstats.settings

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.chip.ChipGroup
import cu.maxwell.firenetstats.MainActivity
import cu.maxwell.firenetstats.R
import cu.maxwell.firenetstats.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var themePreferences: AppThemePreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        themePreferences = AppThemePreferences(requireContext())
        setupThemeChipGroup()
        setupStartScreenChipGroup()
        setupPrimaryColorChipGroup()
        setupStartupControls()
        setupInterceptNotificationsSwitch()
        setupVpnAlwaysOn()
        setupPermissionsCard()
    }

    private fun setupThemeChipGroup() {
        val chipGroup: ChipGroup = binding.chipGroupTheme
        val chipAuto = binding.chipThemeAuto
        val chipLight = binding.chipThemeLight
        val chipDark = binding.chipThemeDark
        
        // Obtener tema actual
        val currentTheme = themePreferences.getThemeMode()
        
        // Variable para rastrear el tema actual y evitar duplicados
        var isInitializing = true
        
        // Listener para cambios en el ChipGroup
        chipGroup.setOnCheckedChangeListener { group, checkedId ->
            if (!isAdded) return@setOnCheckedChangeListener
            
            // Convertir ID del chip a tema
            val newTheme = when (checkedId) {
                chipAuto.id -> AppThemePreferences.THEME_AUTO
                chipLight.id -> AppThemePreferences.THEME_LIGHT
                chipDark.id -> AppThemePreferences.THEME_DARK
                else -> return@setOnCheckedChangeListener
            }
            
            // Si estamos inicializando, solo registramos sin hacer cambios
            if (isInitializing) {
                isInitializing = false
                return@setOnCheckedChangeListener
            }
            
            // Obtener tema actual guardado
            val savedTheme = themePreferences.getThemeMode()
            
            // Solo actuar si el tema cambió (evitar aplicar el mismo tema 2 veces)
            if (newTheme != savedTheme) {
                themePreferences.setThemeMode(newTheme)
                themePreferences.applyTheme()
                
                // Guardar la posición actual del ViewPager en el intent antes de recrear
                val currentActivity = activity
                if (currentActivity is MainActivity) {
                    val currentPosition = currentActivity.binding.viewPager.currentItem
                    currentActivity.intent.putExtra("currentViewPagerPosition", currentPosition)
                }
                
                // Recrear la activity para aplicar el tema
                activity?.recreate()
            }
        }
        
        // Seleccionar el chip correspondiente al tema actual DESPUÉS de configurar el listener
        when (currentTheme) {
            AppThemePreferences.THEME_AUTO -> chipGroup.check(chipAuto.id)
            AppThemePreferences.THEME_LIGHT -> chipGroup.check(chipLight.id)
            AppThemePreferences.THEME_DARK -> chipGroup.check(chipDark.id)
        }
    }

    private fun setupStartScreenChipGroup() {
        val chipGroup: ChipGroup = binding.chipGroupStartScreen
        val chipHome = binding.chipScreenHome
        val chipFirewall = binding.chipScreenFirewall
        val prefs = requireContext().getSharedPreferences("app_start_screen_prefs", Context.MODE_PRIVATE)

        // Obtener pantalla actual (por defecto HOME = 0)
        val currentScreen = prefs.getInt("start_screen", 0)

        // Variable para rastrear el estado inicial y evitar duplicados
        var isInitializing = true

        // Listener para cambios en el ChipGroup
        chipGroup.setOnCheckedChangeListener { group, checkedId ->
            if (!isAdded) return@setOnCheckedChangeListener

            // Convertir ID del chip a pantalla
            val selectedScreen = when (checkedId) {
                chipHome.id -> 0
                chipFirewall.id -> 1
                else -> return@setOnCheckedChangeListener
            }

            // Si estamos inicializando, solo registramos sin hacer cambios
            if (isInitializing) {
                isInitializing = false
                return@setOnCheckedChangeListener
            }

            // Obtener pantalla actual guardada
            val savedScreen = prefs.getInt("start_screen", 0)

            // Solo actuar si la pantalla cambió (evitar aplicar la misma 2 veces)
            if (selectedScreen != savedScreen) {
                prefs.edit().putInt("start_screen", selectedScreen).apply()
            }
        }

        // Seleccionar el chip correspondiente a la pantalla actual DESPUÉS de configurar el listener
        when (currentScreen) {
            0 -> chipGroup.check(chipHome.id)
            1 -> chipGroup.check(chipFirewall.id)
        }
    }

    private fun setupPrimaryColorChipGroup() {
        val chipGroup: ChipGroup = binding.chipGroupPrimaryColor
        val chipDefault = binding.chipColorDefault
        val chipBlue = binding.chipColorBlue
        val chipRed = binding.chipColorRed
        val chipGreen = binding.chipColorGreen
        val chipYellow = binding.chipColorYellow
        val chipPurple = binding.chipColorPurple
        val chipOrange = binding.chipColorOrange
        val colorPrefs = AppPrimaryColorPreferences(requireContext())

        // Obtener color actual
        val currentColorIndex = colorPrefs.getPrimaryColorIndex()

        // Variable para rastrear el estado inicial y evitar duplicados
        var isInitializing = true

        // Listener para cambios en el ChipGroup
        chipGroup.setOnCheckedChangeListener { group, checkedId ->
            if (!isAdded) return@setOnCheckedChangeListener

            // Convertir ID del chip a índice de color
            val selectedColorIndex = when (checkedId) {
                chipDefault.id -> AppPrimaryColorPreferences.COLOR_DEFAULT
                chipBlue.id -> AppPrimaryColorPreferences.COLOR_BLUE
                chipRed.id -> AppPrimaryColorPreferences.COLOR_RED
                chipGreen.id -> AppPrimaryColorPreferences.COLOR_GREEN
                chipYellow.id -> AppPrimaryColorPreferences.COLOR_YELLOW
                chipPurple.id -> AppPrimaryColorPreferences.COLOR_PURPLE
                chipOrange.id -> AppPrimaryColorPreferences.COLOR_ORANGE
                else -> return@setOnCheckedChangeListener
            }

            // Si estamos inicializando, solo registramos sin hacer cambios
            if (isInitializing) {
                isInitializing = false
                return@setOnCheckedChangeListener
            }

            // Obtener color actual guardado
            val savedColorIndex = colorPrefs.getPrimaryColorIndex()

            // Solo actuar si el color cambió
            if (selectedColorIndex != savedColorIndex) {
                colorPrefs.setPrimaryColorIndex(selectedColorIndex)

                // Aplicar el nuevo color y recrear la activity
                val currentActivity = activity
                if (currentActivity is MainActivity) {
                    val currentPosition = currentActivity.binding.viewPager.currentItem
                    currentActivity.intent.putExtra("currentViewPagerPosition", currentPosition)
                }
                activity?.recreate()
            }
        }

        // Seleccionar el chip correspondiente al color actual DESPUÉS de configurar el listener
        when (currentColorIndex) {
            AppPrimaryColorPreferences.COLOR_DEFAULT -> chipGroup.check(chipDefault.id)
            AppPrimaryColorPreferences.COLOR_BLUE -> chipGroup.check(chipBlue.id)
            AppPrimaryColorPreferences.COLOR_RED -> chipGroup.check(chipRed.id)
            AppPrimaryColorPreferences.COLOR_GREEN -> chipGroup.check(chipGreen.id)
            AppPrimaryColorPreferences.COLOR_YELLOW -> chipGroup.check(chipYellow.id)
            AppPrimaryColorPreferences.COLOR_PURPLE -> chipGroup.check(chipPurple.id)
            AppPrimaryColorPreferences.COLOR_ORANGE -> chipGroup.check(chipOrange.id)
        }
    }

    private fun setupStartupControls() {
        if (!isAdded) return

        val startupPrefs = AppStartupPreferences(requireContext())
        val switchStartup = binding.switchStartupSystem
        val componentsContainer = binding.startupComponentsContainer
        val chipGroup = binding.chipGroupStartupComponents
        val chipWidget = binding.chipStartupWidget
        val chipFirewall = binding.chipStartupFirewall
        val chipBoth = binding.chipStartupBoth

        // Cargar estado guardado
        val startupEnabled = startupPrefs.isStartupEnabled()
        val componentChoice = startupPrefs.getStartupComponent()

        switchStartup.isChecked = startupEnabled
        componentsContainer.visibility = if (startupEnabled) View.VISIBLE else View.GONE
        
        // Variable para rastrear el estado inicial y evitar duplicados
        var isInitializing = true
        
        // Listener para cambios en el ChipGroup
        chipGroup.setOnCheckedChangeListener { group, checkedId ->
            if (!isAdded) return@setOnCheckedChangeListener
            
            // Convertir ID del chip a componente
            val newComponentChoice = when (checkedId) {
                chipWidget.id -> AppStartupPreferences.COMPONENT_WIDGET
                chipFirewall.id -> AppStartupPreferences.COMPONENT_FIREWALL
                chipBoth.id -> AppStartupPreferences.COMPONENT_BOTH
                else -> return@setOnCheckedChangeListener
            }
            
            // Si estamos inicializando, solo registramos sin hacer cambios
            if (isInitializing) {
                isInitializing = false
                return@setOnCheckedChangeListener
            }
            
            // Obtener componente actual guardado
            val savedComponent = startupPrefs.getStartupComponent()
            
            // Solo actuar si el componente cambió (evitar aplicar el mismo 2 veces)
            if (newComponentChoice != savedComponent) {
                startupPrefs.setStartupComponent(newComponentChoice)
            }
        }
        
        // Seleccionar el chip correspondiente al componente actual DESPUÉS de configurar el listener
        when (componentChoice) {
            AppStartupPreferences.COMPONENT_WIDGET -> chipGroup.check(chipWidget.id)
            AppStartupPreferences.COMPONENT_FIREWALL -> chipGroup.check(chipFirewall.id)
            AppStartupPreferences.COMPONENT_BOTH -> chipGroup.check(chipBoth.id)
            else -> chipGroup.check(chipWidget.id)
        }

        // Switch principal: mostrar/ocultar subsección
        switchStartup.setOnCheckedChangeListener { _, isChecked ->
            if (!isAdded) return@setOnCheckedChangeListener
            startupPrefs.setStartupEnabled(isChecked)
            componentsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun setupInterceptNotificationsSwitch() {
        if (!isAdded) return

        val interceptPrefs = cu.maxwell.firenetstats.firewall.AppInterceptPreferences(requireContext())
        val switchIntercept = binding.switchInterceptNotifications

        // Cargar estado guardado
        val interceptEnabled = interceptPrefs.isInterceptNotificationsEnabled()
        
        // Actualizar estado del switch basado en si el permiso de notificaciones está concedido
        val notificationPermissionGranted = isNotificationPermissionGranted()
        
        // Si el permiso no está concedido pero el switch está activado, desactivarlo
        if (interceptEnabled && !notificationPermissionGranted) {
            switchIntercept.isChecked = false
        } else {
            switchIntercept.isChecked = interceptEnabled && notificationPermissionGranted
        }

        // Listener para cambios
        switchIntercept.setOnCheckedChangeListener { _, isChecked ->
            if (!isAdded) return@setOnCheckedChangeListener
            
            if (isChecked) {
                // Si el usuario intenta activar pero no tiene permiso, solicitar
                if (!isNotificationPermissionGranted()) {
                    switchIntercept.isChecked = false
                    requestPostNotificationsPermission()
                } else {
                    interceptPrefs.setInterceptNotificationsEnabled(true)
                }
            } else {
                // Desactivar sin problemas
                interceptPrefs.setInterceptNotificationsEnabled(false)
            }
        }
    }

    private fun setupVpnAlwaysOn() {
        if (!isAdded) return

        val vpnContainer = binding.vpnAlwaysOnContainer
        vpnContainer.setOnClickListener {
            openVpnSettings()
        }
    }

    private fun setupPermissionsCard() {
        if (!isAdded) return

        // Ubicación Precisa
        updatePermissionStatus(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            binding.permissionLocationFineStatus,
            binding.permissionLocationFineButton,
            LOCATION_PERMISSION_REQUEST_CODE
        )

        binding.permissionLocationFineButton.setOnClickListener {
            requestPermissionsForSettings(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // Ubicación General
        updatePermissionStatus(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            binding.permissionLocationCoarseStatus,
            binding.permissionLocationCoarseButton,
            LOCATION_PERMISSION_REQUEST_CODE
        )

        binding.permissionLocationCoarseButton.setOnClickListener {
            requestPermissionsForSettings(
                arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // Permiso de Datos de Uso
        updateUsageStatsPermissionStatus()

        binding.permissionUsageStatsButton.setOnClickListener {
            requestUsageStatsPermission()
        }

        // Permiso VPN
        val vpnGranted = isVpnPermissionGranted()
        updateVpnPermissionStatus(vpnGranted)

        binding.permissionVpnButton.setOnClickListener {
            requestVpnPermission()
        }

        // Ventanas Superpuestas
        updateOverlayPermissionStatus()

        binding.permissionOverlayButton.setOnClickListener {
            requestOverlayPermission()
        }

        // Notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationGranted = isNotificationPermissionGranted()
            updateNotificationPermissionStatus(notificationGranted)

            binding.permissionNotificationsButton.setOnClickListener {
                requestPostNotificationsPermission()
            }
        } else {
            // Ocultar el permiso de notificaciones en Android 12 y anteriores
            binding.permissionNotificationsRow.visibility = android.view.View.GONE
        }
    }

    private fun updatePermissionStatus(
        permission: String,
        statusIcon: android.widget.ImageView,
        button: com.google.android.material.button.MaterialButton,
        requestCode: Int
    ) {
        val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(),
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (isGranted) {
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusIcon.setColorFilter(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.firewall_green),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            statusIcon.visibility = android.view.View.VISIBLE
            button.visibility = android.view.View.GONE
        } else {
            statusIcon.visibility = android.view.View.GONE
            button.visibility = android.view.View.VISIBLE
        }
    }

    private fun updateVpnPermissionStatus(isGranted: Boolean) {
        if (isGranted) {
            binding.permissionVpnStatus.setImageResource(R.drawable.ic_check_circle)
            binding.permissionVpnStatus.setColorFilter(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.firewall_green),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            binding.permissionVpnStatus.visibility = android.view.View.VISIBLE
            binding.permissionVpnButton.visibility = android.view.View.GONE
        } else {
            binding.permissionVpnStatus.visibility = android.view.View.GONE
            binding.permissionVpnButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun requestPermissionsForSettings(
        permissions: Array<String>,
        requestCode: Int
    ) {
        androidx.core.app.ActivityCompat.requestPermissions(
            requireActivity(),
            permissions,
            requestCode
        )
    }

    private fun isVpnPermissionGranted(): Boolean {
        return try {
            android.net.VpnService.prepare(requireContext()) == null
        } catch (e: Exception) {
            false
        }
    }

    private fun requestVpnPermission() {
        try {
            val intent = android.net.VpnService.prepare(requireContext())
            if (intent != null) {
                startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                requireContext(),
                "Error solicitando permiso VPN",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_PERMISSION_REQUEST_CODE || requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            // Pequeño delay para asegurar que el permiso se ha procesado
            view?.postDelayed({
                if (isAdded) {
                    refreshPermissionsStatus()
                }
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        // Verificar permisos cuando el fragmento se reanuda
        // Esto es necesario porque algunos permisos (como overlay) no siempre llaman a onActivityResult
        refreshPermissionsStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                // Pequeño delay para asegurar que el permiso se ha procesado
                view?.postDelayed({
                    if (isAdded) {
                        refreshPermissionsStatus()
                    }
                }, 300)
            }
            REQUEST_NOTIFICATION_CODE -> {
                if (grantResults.isNotEmpty() && 
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // Permiso concedido, activar el switch
                    if (isAdded) {
                        binding.switchInterceptNotifications.isChecked = true
                        val interceptPrefs = cu.maxwell.firenetstats.firewall.AppInterceptPreferences(requireContext())
                        interceptPrefs.setInterceptNotificationsEnabled(true)
                        android.widget.Toast.makeText(
                            requireContext(),
                            "✓ Permiso de notificaciones concedido",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // Permiso rechazado
                    if (isAdded) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Se requiere permiso de notificaciones para interceptar intentos de acceso",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                // Actualizar estado de todos los permisos
                if (isAdded) {
                    refreshPermissionsStatus()
                }
            }
        }
    }

    private fun refreshPermissionsStatus() {
        if (!isAdded) return

        updatePermissionStatus(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            binding.permissionLocationFineStatus,
            binding.permissionLocationFineButton,
            LOCATION_PERMISSION_REQUEST_CODE
        )

        updatePermissionStatus(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            binding.permissionLocationCoarseStatus,
            binding.permissionLocationCoarseButton,
            LOCATION_PERMISSION_REQUEST_CODE
        )

        updateVpnPermissionStatus(isVpnPermissionGranted())

        // Usar función específica para datos de uso
        updateUsageStatsPermissionStatus()

        // Usar función específica para overlay
        updateOverlayPermissionStatus()

        // Usar función específica para notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            updateNotificationPermissionStatus(isNotificationPermissionGranted())
        }
    }

    private fun updateOverlayPermissionStatus() {
        val isGranted = android.provider.Settings.canDrawOverlays(requireContext())

        if (isGranted) {
            binding.permissionOverlayStatus.setImageResource(R.drawable.ic_check_circle)
            binding.permissionOverlayStatus.setColorFilter(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.firewall_green),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            binding.permissionOverlayStatus.visibility = android.view.View.VISIBLE
            binding.permissionOverlayButton.visibility = android.view.View.GONE
        } else {
            binding.permissionOverlayStatus.visibility = android.view.View.GONE
            binding.permissionOverlayButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun updateNotificationPermissionStatus(isGranted: Boolean) {
        if (isGranted) {
            binding.permissionNotificationsStatus.setImageResource(R.drawable.ic_check_circle)
            binding.permissionNotificationsStatus.setColorFilter(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.firewall_green),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            binding.permissionNotificationsStatus.visibility = android.view.View.VISIBLE
            binding.permissionNotificationsButton.visibility = android.view.View.GONE
        } else {
            binding.permissionNotificationsStatus.visibility = android.view.View.GONE
            binding.permissionNotificationsButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun updateUsageStatsPermissionStatus() {
        val isGranted = isUsageStatsPermissionGranted()

        if (isGranted) {
            binding.permissionUsageStatsStatus.setImageResource(R.drawable.ic_check_circle)
            binding.permissionUsageStatsStatus.setColorFilter(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.firewall_green),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            binding.permissionUsageStatsStatus.visibility = android.view.View.VISIBLE
            binding.permissionUsageStatsButton.visibility = android.view.View.GONE
        } else {
            binding.permissionUsageStatsStatus.visibility = android.view.View.GONE
            binding.permissionUsageStatsButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun isUsageStatsPermissionGranted(): Boolean {
        return try {
            val packageManager = requireContext().packageManager
            val appOps = requireContext().getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                requireContext().packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun requestUsageStatsPermission() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                requireContext(),
                "Error solicitando permiso de datos de uso",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = android.net.Uri.parse("package:${requireContext().packageName}")
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                requireContext(),
                "Error solicitando permiso de overlay",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val VPN_PERMISSION_REQUEST_CODE = 101
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 102
        private const val REQUEST_NOTIFICATION_CODE = 103
    }

    private fun openVpnSettings() {
        try {
            val intent = android.content.Intent()
            intent.action = android.provider.Settings.ACTION_VPN_SETTINGS
            startActivity(intent)
        } catch (e: Exception) {
            // Si no hay actividad de VPN settings, abrir configuración general
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                // No hacer nada si no se puede abrir
            }
        }
    }

    // Funciones para manejar permisos de notificaciones
    private fun isNotificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true // En Android 12 y anteriores no es necesario el permiso
    }

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isNotificationPermissionGranted()) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_CODE
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


