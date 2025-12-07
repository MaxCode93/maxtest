package cu.maxwell.firenetstats

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.RequiresApi
import cu.maxwell.firenetstats.databinding.ActivityMainBinding
import cu.maxwell.firenetstats.firewall.FirewallFragment
import cu.maxwell.firenetstats.firewall.AppDataCache
import cu.maxwell.firenetstats.settings.SettingsFragment
import cu.maxwell.firenetstats.settings.InitializationManager
import cu.maxwell.firenetstats.utils.UpdateChecker
import cu.maxwell.firenetstats.utils.UpdateState
import cu.maxwell.firenetstats.utils.ForceUpdateChecker
import cu.maxwell.firenetstats.utils.ForceUpdateDialogFragment
import cu.maxwell.firenetstats.firewall.NetStatsFirewallPreferences
import cu.maxwell.firenetstats.settings.AppThemePreferences
import cu.maxwell.firenetstats.utils.ForceUpdateManager
import cu.maxwell.firenetstats.utils.ForceUpdateCacheManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlin.getValue
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var updateChecker: UpdateChecker
    private var isWidgetActive = false
    private var syncTimer: java.util.Timer? = null
    private val firewallPrefs by lazy { NetStatsFirewallPreferences(this) }
    private var firewallButtonState = FirewallButtonState.OFF
    private var firewallAnimationJob: Job? = null
    private var lastSelectedScreen = -1  // Rastrea la última pantalla seleccionada
    private var pendingFirewallStartAfterPermission = false
    private var pendingUiActivationAfterPermission = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PHONE_STATE_PERMISSION_REQUEST_CODE = 1002
        private const val ALL_PERMISSIONS_REQUEST_CODE = 1003
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234
        private const val VPN_PERMISSION_REQUEST_CODE = 101
    }

    private enum class FirewallButtonState {
        OFF,
        ACTIVATING,
        ON,
        DEACTIVATING
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Aplicar el tema guardado antes de crear la vista
        val themePreferences = AppThemePreferences(this)
        themePreferences.applyTheme()

        // Aplicar el color primario guardado
        val colorPrefs = cu.maxwell.firenetstats.settings.AppPrimaryColorPreferences(this)
        val styleRes = colorPrefs.getStyleResId(colorPrefs.getPrimaryColorIndex())
        setTheme(styleRes)
        
        // Realizar inicialización por primera vez (bloquear todas las apps)
        val initManager = InitializationManager(this)
        initManager.performFirstRunInitializationIfNeeded()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Verificar si se debe cerrar la app (desde el widget)
        if (intent.getBooleanExtra("EXIT", false)) {
            finish()
            return
        }

        // Inicializar update checker
        updateChecker = UpdateChecker(this)

        // Resetear cache de actualización forzada si versión cambió
        resetForceUpdateCacheIfVersionChanged()

        // VERIFICAR ACTUALIZACIÓN FORZADA (antes de todo)
        checkForceUpdate()

        // Verificar permisos
        checkAndRequestAllPermissions()

        setupViewPager(savedInstanceState)
        setupBottomNavigation()
        setupTopBarButtons()
        setupFirewallButton()

        // Precargar apps del firewall en background
        preloadFirewallApps()

        // Inicializar: ocultar el botón de actualización por defecto
        binding.btnUpdateContainer.visibility = android.view.View.GONE

        // Verificar actualizaciones disponibles
        checkForUpdates()

        // Inicializar lastSelectedScreen
        val prefs = getSharedPreferences("app_start_screen_prefs", MODE_PRIVATE)
        lastSelectedScreen = if (savedInstanceState != null && savedInstanceState.containsKey("lastSelectedScreen")) {
            savedInstanceState.getInt("lastSelectedScreen")
        } else {
            prefs.getInt("start_screen", 0)
        }

        val filter = IntentFilter("cu.maxwell.firenetstats.SERVICE_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                serviceStateReceiver,
                filter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(serviceStateReceiver, filter)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("lastSelectedScreen", lastSelectedScreen)
        // Guardar la posición actual del ViewPager
        outState.putInt("currentViewPagerPosition", binding.viewPager.currentItem)
    }

    override fun onResume() {
        super.onResume()

        // Verificar si la pantalla de inicio cambió desde los ajustes
        val prefs = getSharedPreferences("app_start_screen_prefs", MODE_PRIVATE)
        val currentScreen = prefs.getInt("start_screen", 0)
        
        if (lastSelectedScreen != currentScreen) {
            lastSelectedScreen = currentScreen
            // Si cambió la pantalla, actualizar el ViewPager sin animación
            // Esto disparará el OnPageChangeCallback
            binding.viewPager.setCurrentItem(currentScreen, false)
        } else {
            // Si no cambió pero estamos en onResume, asegurar que el header está actualizado
            // por si acaso volvimos desde settings y algo necesita refrescarse
            val currentPosition = binding.viewPager.currentItem
            updateHeaderForPosition(currentPosition)
        }

        // Auto-recuperar VPN si debería estar activo pero no lo está (útil después de reinstalación por debugger)
        if (firewallPrefs.isFirewallEnabled() && !isVpnServiceRunning()) {
            if (isAnotherVpnActive()) {
                android.util.Log.i(
                    "MainActivity",
                    "Se detectó otra VPN activa, no se reiniciará el firewall"
                )
            } else {
                android.util.Log.i("MainActivity", "VPN debería estar activo pero no lo está. Reiniciando...")
                enableFirewall()
            }
        }

        // Verificar el estado real del servicio y actualizar la UI
        val realServiceState = getServiceRealState()
        if (isWidgetActive != realServiceState) {
            isWidgetActive = realServiceState
            // No hay botón de widget en el header, pero podemos mantener la lógica
        }

        // Informar al servicio que la app principal está en primer plano
        if (isWidgetActive) {
            val intent = Intent(this, FloatingWidgetService::class.java)
            intent.action = "MAIN_APP_FOREGROUND"
            startService(intent)
        }

        // Verificar estado del widget
        val realState = getServiceRealState()
        if (isWidgetActive != realState) {
            isWidgetActive = realState
        }

        // Programar una sincronización periódica cada 5 segundos
        schedulePeriodicSync()
    }

    override fun onPause() {
        super.onPause()

        // Informar al servicio que la app principal está en segundo plano
        if (isWidgetActive) {
            val intent = Intent(this, FloatingWidgetService::class.java)
            intent.action = "MAIN_APP_BACKGROUND"
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        syncTimer?.cancel()

        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {
            // Ignorar si no estaba registrado
        }
    }

    private fun setupViewPager(savedInstanceState: Bundle?) {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter
        
        // Cargar la pantalla de inicio guardada O la posición actual si se está recreando
        val prefs = getSharedPreferences("app_start_screen_prefs", MODE_PRIVATE)
        val startScreen = prefs.getInt("start_screen", 0) // Default: HOME (0)
        
        // Restaurar posición del ViewPager si la activity se está recreando (ej: cambio de tema)
        var screenToLoad = startScreen
        
        // Primero intentar desde savedInstanceState (si la activity se recrea por cambio de tema del sistema)
        if (savedInstanceState != null && savedInstanceState.containsKey("currentViewPagerPosition")) {
            screenToLoad = savedInstanceState.getInt("currentViewPagerPosition", startScreen)
        }
        // Si no, intentar desde el intent (si se recrea desde SettingsFragment)
        else if (intent.hasExtra("currentViewPagerPosition")) {
            screenToLoad = intent.getIntExtra("currentViewPagerPosition", startScreen)
            intent.removeExtra("currentViewPagerPosition") // Limpiar después de usar
        }
        // Si estamos recreando la actividad (por cambio de tema), mantener la posición actual
        else if (isChangingConfigurations) {
            screenToLoad = startScreen
        }
        
        // Asegurar que la posición esté dentro del rango válido
        screenToLoad = screenToLoad.coerceAtMost(3) // Máximo 3 (Settings)

        binding.viewPager.setCurrentItem(screenToLoad, false) // false = sin animación
        binding.bottomNavigationView.menu.getItem(screenToLoad).isChecked = true
        
        // Actualizar el header inicial (importante cuando se recrea la activity por cambio de tema)
        updateHeaderForPosition(screenToLoad)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.bottomNavigationView.menu.getItem(position).isChecked = true
                
                // Cambiar subtítulo con animación
                val subtitle = when (position) {
                    0 -> getString(R.string.subtitle_home)
                    1 -> getString(R.string.subtitle_firewall)
                    2 -> "Logs de Firewall"
                    3 -> getString(R.string.subtitle_settings)
                    else -> getString(R.string.app_subtitle)
                }
                
                // Aplicar animación de fade + slide al cambiar subtítulo
                binding.tvAppSubtitle.apply {
                    alpha = 0.5f
                    translationY = 5f
                    animate()
                        .alpha(0.85f)
                        .translationY(0f)
                        .setDuration(300)
                        .withStartAction {
                            text = subtitle
                        }
                        .start()
                }
                
                // Actualizar visibilidad de botones
                updateHeaderForPosition(position)
            }
        })
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            // Agregar animación al cambiar item
            item.icon?.let { icon ->
                val animation = android.view.animation.ScaleAnimation(
                    0.8f, 1.1f, 0.8f, 1.1f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 200
                    repeatCount = 1
                    repeatMode = android.view.animation.Animation.REVERSE
                }
            }
            
            when (item.itemId) {
                R.id.navigation_home -> {
                    binding.viewPager.currentItem = 0
                    true
                }
                R.id.navigation_firewall -> {
                    binding.viewPager.currentItem = 1
                    true
                }
                R.id.navigation_settings -> {
                    binding.viewPager.currentItem = 3
                    true
                }
                else -> false
            }
        }
    }

    private fun updateHeaderForPosition(position: Int) {
        // Cambiar subtítulo
        val subtitle = when (position) {
            0 -> getString(R.string.subtitle_home)
            1 -> getString(R.string.subtitle_firewall)
            2 -> getString(R.string.subtitle_settings)
            else -> getString(R.string.app_subtitle)
        }
        binding.tvAppSubtitle.text = subtitle
        
        // Actualizar visibilidad de botones
        binding.btnWidgetConfig.visibility = if (position == 0) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        binding.firewallToggleButton.visibility = if (position == 1) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        binding.btnUpdateContainer.visibility = if (position == 1 || position == 2) {
            android.view.View.GONE
        } else {
            if (updateChecker.getUpdateInfo().available) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        binding.btnAboutInfo.visibility = if (position == 1 || position == 2) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
    }

    private fun setupFirewallButton() {
        firewallButtonState = if (firewallPrefs.isFirewallEnabled()) {
            FirewallButtonState.ON
        } else {
            FirewallButtonState.OFF
        }

        applyFirewallState(firewallButtonState, animateIcon = false)

        binding.firewallToggleButton.apply {
            setOnClickListener {
                when (firewallButtonState) {
                    FirewallButtonState.OFF -> {
                        pulseButtonPress()
                        startFirewallActivationFlow()
                    }
                    FirewallButtonState.ON -> {
                        pulseButtonPress()
                        startFirewallDeactivationFlow()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun pulseButtonPress() {
        val card = binding.firewallToggleButton
        card.scaleX = 0.95f
        card.scaleY = 0.95f
        
        SpringAnimation(card, SpringAnimation.SCALE_X, 1f).apply {
            spring.stiffness = SpringForce.STIFFNESS_HIGH
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            start()
        }
        SpringAnimation(card, SpringAnimation.SCALE_Y, 1f).apply {
            spring.stiffness = SpringForce.STIFFNESS_HIGH
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            start()
        }
    }

    private fun applyFirewallState(state: FirewallButtonState, animateIcon: Boolean = true) {
        val card = binding.firewallToggleButton
        val icon = binding.firewallToggleIcon
        val progress = binding.firewallToggleProgress
        val check = binding.firewallToggleCheck

        val grey = ContextCompat.getColor(this, R.color.icon_grey_disabled)
        val green = ContextCompat.getColor(this, R.color.firewall_green)

        when (state) {
            FirewallButtonState.OFF -> {
                // Mostrar icono gris
                progress.isVisible = false
                check.isVisible = false
                icon.isVisible = true
                icon.setColorFilter(grey, android.graphics.PorterDuff.Mode.SRC_IN)
                
                if (animateIcon) {
                    icon.alpha = 0f
                    icon.animate().alpha(1f).setDuration(300).start()
                }
            }

            FirewallButtonState.ACTIVATING -> {
                // Ocultar icono, mostrar loading
                icon.isVisible = false
                check.isVisible = false
                progress.isVisible = true
                progress.alpha = 1f
            }

            FirewallButtonState.ON -> {
                // Mostrar icono verde
                progress.isVisible = false
                check.isVisible = false
                icon.isVisible = true
                icon.setColorFilter(green, android.graphics.PorterDuff.Mode.SRC_IN)
                
                if (animateIcon) {
                    icon.alpha = 0f
                    icon.animate().alpha(1f).setDuration(300).start()
                }
            }

            FirewallButtonState.DEACTIVATING -> {
                // Ocultar icono, mostrar loading
                icon.isVisible = false
                check.isVisible = false
                progress.isVisible = true
                progress.alpha = 1f
            }
        }
        
        card.isEnabled = state == FirewallButtonState.OFF || state == FirewallButtonState.ON
    }

    private fun showCheckAndReturnToIcon(isActivation: Boolean) {
        val icon = binding.firewallToggleIcon
        val check = binding.firewallToggleCheck
        val progress = binding.firewallToggleProgress
        
        val checkColor = if (isActivation) 
            ContextCompat.getColor(this, R.color.firewall_green)
        else 
            ContextCompat.getColor(this, R.color.firewall_yellow)

        // Ocultar loading, mostrar check
        progress.animate().alpha(0f).setDuration(150).withEndAction {
            progress.isVisible = false
        }.start()

        check.setColorFilter(checkColor, android.graphics.PorterDuff.Mode.SRC_IN)
        check.scaleX = 0.5f
        check.scaleY = 0.5f
        check.alpha = 0f
        check.isVisible = true

        check.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(android.view.animation.OvershootInterpolator())
            .withEndAction {
                // Esperar 3 segundos antes de volver al icono
                lifecycleScope.launch {
                    delay(3000)
                    
                    check.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            check.isVisible = false
                            
                            val iconColor = if (isActivation) 
                                ContextCompat.getColor(this@MainActivity, R.color.firewall_green)
                            else 
                                ContextCompat.getColor(this@MainActivity, R.color.icon_grey_disabled)
                            
                            icon.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN)
                            icon.isVisible = true
                            icon.alpha = 0f
                            
                            icon.animate()
                                .alpha(1f)
                                .setDuration(300)
                                .withEndAction {
                                    // Actualizar el estado después de completar la animación
                                    if (isActivation) {
                                        firewallButtonState = FirewallButtonState.ON
                                    } else {
                                        firewallButtonState = FirewallButtonState.OFF
                                    }
                                    // Habilitar el botón
                                    binding.firewallToggleButton.isEnabled = true
                                }
                                .start()
                        }
                        .start()
                }
            }
            .start()
    }

    private fun startFirewallActivationFlow() {
        ensureVpnPermission(
            onGranted = { beginFirewallActivation() },
            showDialogIfMissing = true,
            requireUiActivation = true
        )
    }

    private fun startFirewallDeactivationFlow() {
        firewallAnimationJob?.cancel()
        firewallButtonState = FirewallButtonState.DEACTIVATING
        applyFirewallState(firewallButtonState)
        cu.maxwell.firenetstats.firewall.NetStatsFirewallVpnService.stopVpn(this)

        firewallAnimationJob = lifecycleScope.launch {
            delay(5000)
            showCheckAndReturnToIcon(isActivation = false)
            firewallButtonState = FirewallButtonState.OFF
            firewallPrefs.setFirewallEnabled(false)
        }
    }

    private fun beginFirewallActivation() {
        firewallAnimationJob?.cancel()
        firewallButtonState = FirewallButtonState.ACTIVATING
        applyFirewallState(firewallButtonState)
        startFirewallService()

        firewallAnimationJob = lifecycleScope.launch {
            delay(5000)
            firewallButtonState = FirewallButtonState.ON
            showCheckAndReturnToIcon(isActivation = true)
            firewallPrefs.setFirewallEnabled(true)
        }
    }

    private fun enableFirewall() {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            pendingFirewallStartAfterPermission = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
        } else {
            startFirewallService()
            pendingFirewallStartAfterPermission = false
            pendingUiActivationAfterPermission = false
        }
    }

    private fun startFirewallService() {
        val vpnIntent = Intent(this, cu.maxwell.firenetstats.firewall.NetStatsFirewallVpnService::class.java)
        startService(vpnIntent)
    }

    private fun ensureVpnPermission(
        onGranted: () -> Unit,
        showDialogIfMissing: Boolean,
        requireUiActivation: Boolean
    ) {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            pendingFirewallStartAfterPermission = true
            pendingUiActivationAfterPermission = requireUiActivation

            if (showDialogIfMissing) {
                showVpnPermissionRequiredDialog()
                resetFirewallToggleState()
            }

            @Suppress("DEPRECATION")
            startActivityForResult(intent, VPN_PERMISSION_REQUEST_CODE)
        } else {
            onGranted()
        }
    }

    private fun resetFirewallToggleState() {
        firewallAnimationJob?.cancel()
        firewallButtonState = FirewallButtonState.OFF
        applyFirewallState(firewallButtonState)
    }

    private fun showVpnPermissionRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.vpn_permission_required_title))
            .setMessage(getString(R.string.vpn_permission_required_message))
            .setPositiveButton(getString(R.string.vpn_permission_required_positive)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment()
                1 -> FirewallFragment()
                2 -> SettingsFragment()
                else -> HomeFragment()
            }
        }
    }

    private fun checkAndRequestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Verificar permiso de ubicación
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            // Verificar permiso de estado del teléfono
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
            }
        }

        // Verificar permiso de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Si hay permisos que solicitar, hacerlo
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                ALL_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permiso concedido, notificar al fragment actual
                    notifyCurrentFragmentPermissionGranted()
                } else {
                    Toast.makeText(
                        this,
                        "Se requieren permisos de ubicación para mostrar el nombre de la red WiFi",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            PHONE_STATE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    notifyCurrentFragmentPermissionGranted()
                } else {
                    Toast.makeText(
                        this,
                        "Se requiere permiso para acceder a información detallada de la red móvil",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            ALL_PERMISSIONS_REQUEST_CODE -> {
                var allGranted = true

                for (i in permissions.indices) {
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false

                        when (permissions[i]) {
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION -> {
                                Toast.makeText(
                                    this,
                                    "Se requieren permisos de ubicación para mostrar el nombre de la red WiFi",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            Manifest.permission.READ_PHONE_STATE -> {
                                Toast.makeText(
                                    this,
                                    "Se requiere permiso para acceder a información detallada de la red móvil",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }

                if (allGranted) {
                    notifyCurrentFragmentPermissionGranted()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_PERMISSION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                if (pendingUiActivationAfterPermission) {
                    pendingFirewallStartAfterPermission = false
                    pendingUiActivationAfterPermission = false
                    beginFirewallActivation()
                } else if (pendingFirewallStartAfterPermission) {
                    pendingFirewallStartAfterPermission = false
                    pendingUiActivationAfterPermission = false
                    startFirewallService()
                }
            } else {
                pendingFirewallStartAfterPermission = false
                pendingUiActivationAfterPermission = false
                resetFirewallToggleState()
            }
        }
    }

    private fun notifyCurrentFragmentPermissionGranted() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.viewPager)
        if (currentFragment is HomeFragment) {
            // El fragment se actualizará automáticamente en onResume
        }
    }

    private fun setupTopBarButtons() {
        // Configurar botón de actualización
        binding.btnUpdate.setOnClickListener {
            showUpdateDialog()
        }

        // Configurar botón de ajustes del widget
        binding.btnWidgetConfig.setOnClickListener {
            // Abrir actividad de configuración del widget
            val intent = Intent(this, WidgetSettingsActivity::class.java)
            startActivity(intent)
        }

        // Configurar botón de información
        binding.btnAboutInfo.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun showAboutDialog() {
        val dialog = Dialog(this, R.style.Theme_FireNetStats_Dialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_about)

        // Obtener la versión de la app
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName

        // Configurar la versión
        val tvVersion = dialog.findViewById<TextView>(R.id.tvVersion)
        tvVersion?.text = "Versión $versionName"

        // Configurar botones de redes sociales
        dialog.findViewById<ImageView>(R.id.btnGithub)?.setOnClickListener {
            openUrl("https://github.com/MaxCode93")
        }

        dialog.findViewById<ImageView>(R.id.btnFacebook)?.setOnClickListener {
            openUrl("https://facebook.com/carlos.maxwell93")
        }

        dialog.findViewById<ImageView>(R.id.btnWhatsapp)?.setOnClickListener {
            openWhatsApp("+5355770892")
        }

        dialog.findViewById<ImageView>(R.id.btnRate)?.setOnClickListener {
            openApkLisDownload()
        }

        dialog.findViewById<ImageView>(R.id.btnShare)?.setOnClickListener {
            shareApp()
        }

        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDonate)?.setOnClickListener {
            showTransfermovilDialog()
        }

        // Historial de cambios
       /* dialog.findViewById<Button>(R.id.btnChangelog)?.setOnClickListener {
            showChangelogDialog()
        }*/

        // Botón cerrar
        dialog.findViewById<Button>(R.id.btnClose)?.setOnClickListener {
            dialog.dismiss()
        }

        // Configurar tamaño del diálogo
        val window = dialog.window
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.show()
    }

    private fun showTransfermovilDialog() {
        val dialog = Dialog(this, R.style.Theme_FireNetStats_Dialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_transfermovil)

        // Copiar número al hacer clic en el botón
        dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyNumber)?.setOnClickListener {
            copyToClipboard("+5355770892")
            Toast.makeText(this, "Número copiado", Toast.LENGTH_SHORT).show()
        }

        // Configurar tamaño del diálogo
        val window = dialog.window
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText("Donation", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "text/plain"
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
        val shareMessage = "¡Prueba FireNetStats, una excelente app para controlar y monitorear tu red! " +
                "Descárgala desde: https://www.apklis.cu/application/cu.maxwell.firenetstats"
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage)
        startActivity(Intent.createChooser(shareIntent, "Compartir vía"))
    }

    private fun openApkLisDownload() {
        try {
            val uri = Uri.parse("https://www.apklis.cu/application/cu.maxwell.firenetstats")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir el navegador", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWhatsApp(phoneNumber: String) {
        try {
            val url = "https://wa.me/$phoneNumber?text=Hola%2C%20tengo%20una%20pregunta%20sobre%20FireNetStats"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show()
        }
    }

    // Widget management methods
    private fun getServiceRealState(): Boolean {
        try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val isServiceRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == FloatingWidgetService::class.java.name }

            val staticState = FloatingWidgetService.isRunning

            if (isServiceRunning == staticState) {
                return isServiceRunning
            }

            return isServiceRunning
        } catch (e: Exception) {
            return FloatingWidgetService.isRunning
        }
    }

    private fun isVpnServiceRunning(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == cu.maxwell.firenetstats.firewall.NetStatsFirewallVpnService::class.java.name }
    }

    private fun isAnotherVpnActive(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return connectivityManager.allNetworks.any { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun schedulePeriodicSync() {
        syncTimer?.cancel()
        syncTimer = null

        syncTimer = java.util.Timer()
        syncTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val realState = getServiceRealState()

                if (isWidgetActive != realState) {
                    runOnUiThread {
                        isWidgetActive = realState
                    }
                }
            }
        }, 5000, 5000)
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val running = intent.getBooleanExtra("RUNNING", false)

            val realState = getServiceRealState()

            if (realState == running) {
                isWidgetActive = running
            } else {
                isWidgetActive = realState
            }
        }
    }

    // ======================== UPDATE SYSTEM ========================

    private fun checkForUpdates() {
        updateChecker.checkForUpdates(object : UpdateChecker.UpdateCheckListener {
            override fun onUpdateAvailable(updateState: UpdateState) {
                runOnUiThread {
                    showUpdateIcon(true)
                }
            }

            override fun onNoUpdate() {
                runOnUiThread {
                    showUpdateIcon(false)
                }
            }

            override fun onCheckError(error: String) {
                runOnUiThread {
                    showUpdateIcon(false)
                }
            }
        }, forceCheck = true)
    }

    private fun showUpdateIcon(show: Boolean) {
        if (show) {
            binding.btnUpdateContainer.visibility = android.view.View.VISIBLE
            val pulseAnimation = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_animation)
            binding.updateBadge.startAnimation(pulseAnimation)
        } else {
            binding.btnUpdateContainer.visibility = android.view.View.GONE
            binding.updateBadge.clearAnimation()
        }
    }

    private fun showUpdateDialog() {
        val updateState = updateChecker.getUpdateInfo()

        if (!updateState.available) {
            Toast.makeText(this, getString(R.string.update_no_new), Toast.LENGTH_SHORT).show()
            binding.btnUpdateContainer.visibility = android.view.View.GONE
            return
        }

        val dialog = Dialog(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update)

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersion = packageInfo.versionName

        val tvUpdateTitle = dialog.findViewById<TextView>(R.id.tvUpdateTitle)
        val tvCurrentVersion = dialog.findViewById<TextView>(R.id.tvCurrentVersion)
        val tvNewVersion = dialog.findViewById<TextView>(R.id.tvNewVersion)
        val tvChangelog = dialog.findViewById<TextView>(R.id.tvChangelog)
        val cbNotShowAgain = dialog.findViewById<CheckBox>(R.id.cbNotShowAgain)
        val btnDownload = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDownload)
        val btnLater = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLater)

        tvUpdateTitle?.text = "✨ Nueva versión disponible"
        tvCurrentVersion?.text = currentVersion
        tvNewVersion?.text = updateState.latestVersion

        val formattedChangelog = formatChangelogMessage(updateState.changelog)
        tvChangelog?.text = formattedChangelog
        tvChangelog?.setLineSpacing(4f, 1.2f)

        btnDownload?.setOnClickListener {
            if (cbNotShowAgain?.isChecked == true) {
                val sharedPref = getSharedPreferences("fireNetStats_prefs", MODE_PRIVATE)
                sharedPref.edit {putBoolean("skip_update_${updateState.latestVersion}", true) }
            }
            dialog.dismiss()
            openApkLisDownload()
        }

        btnLater?.setOnClickListener {
            dialog.dismiss()
            binding.btnUpdateContainer.visibility = android.view.View.GONE
        }

        val window = dialog.window
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setOnDismissListener {
            binding.btnUpdateContainer.visibility = android.view.View.GONE
        }

        dialog.show()
    }

    private fun formatChangelogMessage(changelog: String): String {
        val lines = changelog.split("\n")
        val formattedLines = mutableListOf<String>()

        formattedLines.add("📦 ${getString(R.string.update_changelog)}:")
        formattedLines.add("")

        for (line in lines) {
            var trimmedLine = line.trim()

            if (trimmedLine.isNotBlank()) {
                while (trimmedLine.startsWith("#")) {
                    trimmedLine = trimmedLine.substring(1).trim()
                }

                while (trimmedLine.startsWith("-")) {
                    trimmedLine = trimmedLine.substring(1).trim()
                }

                while (trimmedLine.startsWith("*")) {
                    trimmedLine = trimmedLine.substring(1).trim()
                }

                if (trimmedLine.isNotBlank()) {
                    if (!trimmedLine.startsWith("•")) {
                        formattedLines.add("• $trimmedLine")
                    } else {
                        formattedLines.add(trimmedLine)
                    }
                }
            }
        }

        formattedLines.add("")
        formattedLines.add("${getString(R.string.update_free_download)}")

        return formattedLines.joinToString("\n")
    }

    private fun preloadFirewallApps() {
        // Cargar apps en background para que estén listas cuando se abra el fragmento de firewall
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val packageManager = packageManager
                val packages = packageManager.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
                val apps = mutableListOf<cu.maxwell.firenetstats.firewall.AppInfo>()

                for (pkgInfo in packages) {
                    val app = pkgInfo.applicationInfo ?: continue

                    // Excluir la propia app del firewall
                    if (app.packageName == packageName) {
                        continue
                    }

                    val appName = packageManager.getApplicationLabel(app).toString()
                    val appIcon = packageManager.getApplicationIcon(app)
                    val isSystemApp = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val hasInternet = pkgInfo.requestedPermissions?.contains("android.permission.INTERNET") == true

                    // Obtener data usage (mensual)
                    val (downloadBytes, uploadBytes, totalBytes) = cu.maxwell.firenetstats.utils.NetworkUtils.getAppDataUsageForUid(
                        this@MainActivity,
                        app.uid,
                        cu.maxwell.firenetstats.utils.NetworkUtils.TimePeriod.MONTHLY
                    )

                    apps.add(cu.maxwell.firenetstats.firewall.AppInfo(
                        appName = appName,
                        packageName = app.packageName,
                        appIcon = appIcon,
                        isSystemApp = isSystemApp,
                        hasInternetPermission = hasInternet,
                        isWifiBlocked = false,
                        isDataBlocked = false,
                        isSelected = false,
                        downloadBytes = downloadBytes,
                        uploadBytes = uploadBytes,
                        totalBytes = totalBytes
                    ))
                }

                // Cachear las apps
                AppDataCache.setCachedApps(apps)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error precargando apps", e)
            }
        }
    }

    /**
     * Resetea el cache de actualización forzada si la versión de la app cambió
     * Esto asegura que se verifique nuevamente después de una actualización
     */
    private fun resetForceUpdateCacheIfVersionChanged() {
        try {
            val prefs = getSharedPreferences("app_version_check", Context.MODE_PRIVATE)
            val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            val savedVersion = prefs.getString("last_installed_version", "")

            if (currentVersion != savedVersion) {
                // Versión cambió, resetear cache de actualización forzada
                ForceUpdateCacheManager.resetCache(this)
                prefs.edit().putString("last_installed_version", currentVersion).apply()
                Log.d("MainActivity", "Versión cambió a $currentVersion - cache de actualización forzada reseteado")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error verificando cambio de versión: ${e.message}", e)
        }
    }

    private fun checkForceUpdate() {
        ForceUpdateChecker.checkForceUpdate(this, object : ForceUpdateChecker.OnForceUpdateListener {
            @RequiresApi(Build.VERSION_CODES.P)
            override fun onForceUpdateChecked(result: ForceUpdateManager.ForceUpdateResult) {

                if (!result.isForced) {
                    //Log.d("ForceUpdateChecker", "No hay actualización forzada. Saliendo.")
                    return
                }

                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val currentVersion = "${packageInfo.versionName ?: "desconocida"} (${packageInfo.longVersionCode})"

                ForceUpdateDialogFragment.newInstance(
                    message = result.message,
                    updateUrl = result.updateUrl,
                    currentVersion = currentVersion
                ).show(supportFragmentManager, "ForceUpdateDialog")
            }

        })
    }
}
