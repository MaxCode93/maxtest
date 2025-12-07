package cu.maxwell.firenetstats.firewall

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationUtils
import android.view.animation.ScaleAnimation
import android.view.inputmethod.InputMethodManager
import android.animation.ObjectAnimator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cu.maxwell.firenetstats.R
import cu.maxwell.firenetstats.database.AppCacheManager
import cu.maxwell.firenetstats.databinding.FragmentFirewallBinding
import cu.maxwell.firenetstats.firewall.NetStatsFirewallVpnService.Companion.ACTION_RULES_UPDATED
import cu.maxwell.firenetstats.firewall.NetStatsFirewallVpnService.Companion.ACTION_STATE_CHANGED
import cu.maxwell.firenetstats.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private enum class FirewallStatusFilter { ALL, ALLOWED, BLOCKED }

private enum class FirewallTypeFilter { ALL, USER, SYSTEM, INTERNET }

class FirewallFragment : Fragment() {

    private var _binding: FragmentFirewallBinding? = null
    private val binding get() = _binding!!

    private lateinit var recyclerView: RecyclerView
    private lateinit var appAdapter: NetStatsFirewallAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var cacheManager: AppCacheManager

    private lateinit var prefs: NetStatsFirewallPreferences
    private var currentMode = NetStatsFirewallMode.VPN

    private val masterAppList = mutableListOf<AppInfo>()
    private var currentSortFilterMode = SortFilterMode.NAME
    private var isSortBlockedFirst = false
    private var currentSearchQuery: String? = null
    private var statusFilter = FirewallStatusFilter.ALL
    private var typeFilter = FirewallTypeFilter.ALL
    private var isStatusFilterBeingUpdated = false
    private var isTypeFilterBeingUpdated = false
    private var isLoading = false

    private var actionMode: ActionMode? = null
    private var isInSelectionMode = false

    private val vpnRequestCode = 101

    private var isFirewallReceiverRegistered = false

    // BroadcastReceiver para escuchar cambios en el estado del firewall VPN
    private val firewallStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STATE_CHANGED -> {
                    // No necesitamos actualizar nada aquí
                }
                ACTION_RULES_UPDATED -> {
                    val packageName = intent.getStringExtra("package_name") ?: return
                    val isBlocked = intent.getBooleanExtra("is_blocked", false)
                    handleExternalRuleUpdate(packageName, isBlocked)
                }
            }
        }
    }

    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            exportSettings(it)
        }
    }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            importSettings(it)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(requireContext(), getString(R.string.notification_permission_required), Toast.LENGTH_LONG).show()
            prefs.setRebootReminder(false)
            requireActivity().invalidateOptionsMenu()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirewallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = NetStatsFirewallPreferences(requireContext())
        cacheManager = AppCacheManager(requireContext())

        isSortBlockedFirst = prefs.isSortBlockedFirst()
        currentSearchQuery = prefs.getSearchQuery().ifBlank { null }
        statusFilter = prefs.getStatusFilter().toStatusFilter()
        typeFilter = prefs.getTypeFilter().toTypeFilter()
        currentSortFilterMode = mapTypeFilterToSortMode(typeFilter)

        loadingContainer = binding.loadingContainer
        recyclerView = binding.recyclerViewApps
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        // Configurar animación suave para cambios en la lista
        recyclerView.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            addDuration = 300
            removeDuration = 300
            moveDuration = 300
            changeDuration = 300
        }

        setupAdapter()
        setupHideKeyboardOnOutsideTouch(binding.root)
        setupFilterControls()
        setupSearchField()

        binding.root.isFocusableInTouchMode = true

        binding.inputSearch.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) v.startAnimation(AnimationUtils.loadAnimation(context, R.anim.fade_border))
        }

        setupMasterToggle()
        loadApps()
        currentSearchQuery?.let { binding.inputSearch.setText(it) }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        unregisterFirewallReceiver()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterFirewallReceiver()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        registerFirewallReceiver()

        // Auto-recuperar VPN si debería estar activo pero no lo está (útil después de reinstalación por debugger)
        if (prefs.isFirewallEnabled() && currentMode == NetStatsFirewallMode.VPN && !isVpnServiceRunning()) {
            Log.i("FirewallFragment", "VPN debería estar activo pero no lo está. Reiniciando...")
            startVpnService()
        }

        refreshAppsFromCache()
    }

    private fun refreshAppsFromCache() {
        if (!isAdded) return

        val existingIcons = masterAppList.associate { it.packageName to it.appIcon }

        lifecycleScope.launch {
            val cachedApps = withContext(Dispatchers.IO) { cacheManager.getAllAppsFromCache() }
            if (!isAdded || cachedApps.isEmpty()) return@launch

            masterAppList.clear()
            masterAppList.addAll(cachedApps.map { app ->
                app.copy(
                    appIcon = existingIcons[app.packageName] ?: app.appIcon,
                    isWifiBlocked = prefs.isWifiBlocked(currentMode, app.packageName),
                    isDataBlocked = prefs.isDataBlocked(currentMode, app.packageName)
                )
            })

            sortAndDisplayApps()
        }
    }

    private fun registerFirewallReceiver() {
        if (!isAdded || isFirewallReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_STATE_CHANGED)
            addAction(ACTION_RULES_UPDATED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                firewallStateReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.registerReceiver(
                requireContext(),
                firewallStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        isFirewallReceiverRegistered = true
    }

    private fun unregisterFirewallReceiver() {
        if (!isFirewallReceiverRegistered) return

        try {
            requireContext().unregisterReceiver(firewallStateReceiver)
        } catch (e: Exception) {
            // Receiver ya desregistrado o contexto no disponible
        } finally {
            isFirewallReceiverRegistered = false
        }
    }

    private fun handleExternalRuleUpdate(packageName: String, isBlocked: Boolean) {
        if (!isAdded) return

        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            val updatedApp = withContext(Dispatchers.IO) {
                try {
                    val packageManager = appContext.packageManager
                    val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                    val appName = packageManager.getApplicationLabel(applicationInfo).toString()
                    val appIcon = packageManager.getApplicationIcon(packageName)
                    val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val hasInternet = packageManager
                        .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions
                        ?.contains(Manifest.permission.INTERNET) == true

                    val (downloadBytes, uploadBytes, totalBytes) = NetworkUtils.getAppDataUsageForUid(
                        appContext,
                        applicationInfo.uid,
                        NetworkUtils.TimePeriod.MONTHLY
                    )

                    AppInfo(
                        appName = appName,
                        packageName = packageName,
                        appIcon = appIcon,
                        isSystemApp = isSystemApp,
                        hasInternetPermission = hasInternet,
                        isWifiBlocked = isBlocked,
                        isDataBlocked = isBlocked,
                        isSelected = false,
                        downloadBytes = downloadBytes,
                        uploadBytes = uploadBytes,
                        totalBytes = totalBytes
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch

            val existingIndex = masterAppList.indexOfFirst { it.packageName == packageName }
            if (existingIndex >= 0) {
                masterAppList[existingIndex] = masterAppList[existingIndex].copy(
                    appName = updatedApp.appName,
                    appIcon = updatedApp.appIcon,
                    isSystemApp = updatedApp.isSystemApp,
                    hasInternetPermission = updatedApp.hasInternetPermission,
                    isWifiBlocked = updatedApp.isWifiBlocked,
                    isDataBlocked = updatedApp.isDataBlocked,
                    downloadBytes = updatedApp.downloadBytes,
                    uploadBytes = updatedApp.uploadBytes,
                    totalBytes = updatedApp.totalBytes,
                    isSelected = false
                )
            } else {
                masterAppList.add(updatedApp)
            }

            withContext(Dispatchers.IO) {
                cacheManager.upsertApp(updatedApp)
            }

            if (!isAdded) return@launch
            sortAndDisplayApps()
        }
    }

    private fun setupAdapter() {
        appAdapter = NetStatsFirewallAdapter(
            emptyList(),
            onItemClick = { app ->
                if (isInSelectionMode) {
                    toggleSelection(app)
                }
            },
            onItemLongClick = { app ->
                if (!isInSelectionMode) {
                    actionMode = (requireActivity() as androidx.appcompat.app.AppCompatActivity)
                        .startSupportActionMode(actionModeCallback)
                }
                toggleSelection(app)
            },
            onToggleClick = { app, shouldBlock ->
                onToggleClicked(app, shouldBlock)
            }
        )
        recyclerView.adapter = appAdapter
    }

    private fun setupFilterControls() {
        updateStatusChipSelection()
        updateTypeChipSelection()

        // Status Filter - Solo permite UNA selección (Radio Button behavior)
        binding.chipStatusAll.setOnCheckedChangeListener { _, isChecked ->
            if (!isStatusFilterBeingUpdated) {
                if (isChecked) {
                    setAllStatusChipsUnchecked(binding.chipStatusAll)
                    animateChipSelection(binding.chipStatusAll)
                    applyStatusFilter(FirewallStatusFilter.ALL)
                } else {
                    // Reactivar automáticamente si se intenta deseleccionar
                    lifecycleScope.launch {
                        delay(100)
                        ensureDefaultSelection()
                        sortAndDisplayApps()
                    }
                }
            }
        }
        binding.chipStatusAllowed.setOnCheckedChangeListener { _, isChecked ->
            if (!isStatusFilterBeingUpdated) {
                if (isChecked) {
                    setAllStatusChipsUnchecked(binding.chipStatusAllowed)
                    animateChipSelection(binding.chipStatusAllowed)
                    applyStatusFilter(FirewallStatusFilter.ALLOWED)
                } else {
                    // Reactivar automáticamente si se intenta deseleccionar
                    lifecycleScope.launch {
                        delay(100)
                        ensureDefaultSelection()
                        sortAndDisplayApps()
                    }
                }
            }
        }
        binding.chipStatusBlocked.setOnCheckedChangeListener { _, isChecked ->
            if (!isStatusFilterBeingUpdated) {
                if (isChecked) {
                    setAllStatusChipsUnchecked(binding.chipStatusBlocked)
                    animateChipSelection(binding.chipStatusBlocked)
                    applyStatusFilter(FirewallStatusFilter.BLOCKED)
                } else {
                    // Reactivar automáticamente si se intenta deseleccionar
                    lifecycleScope.launch {
                        delay(100)
                        ensureDefaultSelection()
                        sortAndDisplayApps()
                    }
                }
            }
        }

        // Type Filter - Solo permite UNA selección (Radio Button behavior)
        binding.chipTypeAll.setOnCheckedChangeListener { _, isChecked ->
            if (!isTypeFilterBeingUpdated) {
                if (isChecked) {
                    setAllTypeChipsUnchecked(binding.chipTypeAll)
                    animateChipSelection(binding.chipTypeAll)
                    applyTypeFilter(FirewallTypeFilter.ALL)
                } else {
                    // Reactivar automáticamente si se intenta deseleccionar
                    lifecycleScope.launch {
                        delay(100)
                        ensureDefaultSelection()
                        sortAndDisplayApps()
                    }
                }
            }
        }
        binding.chipTypeUser.setOnCheckedChangeListener { _, isChecked ->
            if (!isTypeFilterBeingUpdated) {
                if (isChecked) {
                    setAllTypeChipsUnchecked(binding.chipTypeUser)
                    animateChipSelection(binding.chipTypeUser)
                    applyTypeFilter(FirewallTypeFilter.USER)
                } else {
                    lifecycleScope.launch {
                        delay(100)
                        ensureDefaultSelection()
                        sortAndDisplayApps()
                    }
                }
            }
        }
        binding.chipTypeSystem.setOnCheckedChangeListener { _, isChecked ->
            if (!isTypeFilterBeingUpdated) {
                if (isChecked) {
                    setAllTypeChipsUnchecked(binding.chipTypeSystem)
                    animateChipSelection(binding.chipTypeSystem)
                    applyTypeFilter(FirewallTypeFilter.SYSTEM)
                } else {
                    lifecycleScope.launch {
                        delay(100)
                        ensureDefaultSelection()
                        sortAndDisplayApps()
                    }
                }
            }
        }
        binding.chipTypeInternet.setOnCheckedChangeListener { _, isChecked ->
            if (!isTypeFilterBeingUpdated) {
                if (isChecked) {
                    setAllTypeChipsUnchecked(binding.chipTypeInternet)
                    animateChipSelection(binding.chipTypeInternet)
                    applyTypeFilter(FirewallTypeFilter.INTERNET)
                } else {
                    lifecycleScope.launch {
                        delay(100)
                        ensureDefaultSelection()
                        sortAndDisplayApps()
                    }
                }
            }
        }
    }

    private fun setupSearchField() {
        // Establecer el icono de búsqueda con el color correcto
        val searchIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_search)?.apply {
            val color = ContextCompat.getColor(requireContext(), R.color.text_secondary)
            setTint(color)
        }
        binding.inputSearch.setCompoundDrawablesWithIntrinsicBounds(
            searchIcon, // izquierda
            null,
            null,
            null
        )

        // Búsqueda en tiempo real
        binding.inputSearch.doAfterTextChanged { text ->
            currentSearchQuery = text?.toString()
            prefs.setSearchQuery(currentSearchQuery.orEmpty())
            sortAndDisplayApps()

            val clearIcon = if (!text.isNullOrEmpty()) {
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_clear)?.apply {
                    val color = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                    setTint(color)
                }
            } else null
            binding.inputSearch.setCompoundDrawablesWithIntrinsicBounds(
                searchIcon, // mantiene el ic_search (izquierda)
                null,
                clearIcon, // derecha
                null
            )
        }

        // Limpiar texto cuando se toque el drawableEnd
        binding.inputSearch.setOnTouchListener { v, event ->
            val drawableEnd = 2 // index: left=0, top=1, right=2, bottom=3
            val clearDrawable = binding.inputSearch.compoundDrawables[drawableEnd] ?: return@setOnTouchListener false

            val clearButtonStart =
                binding.inputSearch.width - binding.inputSearch.paddingEnd - clearDrawable.bounds.width()

            if (event.x >= clearButtonStart) {
                if (event.action == MotionEvent.ACTION_UP) {
                    binding.inputSearch.text?.clear()
                    binding.inputSearch.clearFocus()
                    binding.root.requestFocus()
                    hideKeyboard(binding.inputSearch)
                }
                v.cancelLongPress()
                return@setOnTouchListener true
            }

            false
        }

        // Quitar foco cuando se toca fuera del input
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val outRect = Rect()
                binding.inputSearch.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    binding.inputSearch.clearFocus()
                    hideKeyboard(binding.inputSearch)
                }
            }
            false
        }
    }

    private fun setupHideKeyboardOnOutsideTouch(view: View) {
        if (view !is EditText) {
            view.setOnTouchListener { _, _ ->
                binding.inputSearch.clearFocus()
                hideKeyboard(binding.inputSearch)
                false
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setupHideKeyboardOnOutsideTouch(view.getChildAt(i))
            }
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun setAllStatusChipsUnchecked(except: com.google.android.material.chip.Chip? = null) {
        isStatusFilterBeingUpdated = true
        try {
            if (binding.chipStatusAll != except) binding.chipStatusAll.isChecked = false
            if (binding.chipStatusAllowed != except) binding.chipStatusAllowed.isChecked = false
            if (binding.chipStatusBlocked != except) binding.chipStatusBlocked.isChecked = false
        } finally {
            isStatusFilterBeingUpdated = false
        }
    }

    private fun setAllTypeChipsUnchecked(except: com.google.android.material.chip.Chip? = null) {
        isTypeFilterBeingUpdated = true
        try {
            if (binding.chipTypeAll != except) binding.chipTypeAll.isChecked = false
            if (binding.chipTypeUser != except) binding.chipTypeUser.isChecked = false
            if (binding.chipTypeSystem != except) binding.chipTypeSystem.isChecked = false
            if (binding.chipTypeInternet != except) binding.chipTypeInternet.isChecked = false
        } finally {
            isTypeFilterBeingUpdated = false
        }
    }

    private fun animateChipSelection(chip: com.google.android.material.chip.Chip) {
        // Scale animation: 0.95 -> 1.0 para efecto de "click"
        val scaleAnimation = ScaleAnimation(
            0.95f, 1.0f, // X: from 0.95 to 1.0
            0.95f, 1.0f, // Y: from 0.95 to 1.0
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f, // pivot X (center)
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f  // pivot Y (center)
        ).apply {
            duration = 150 // 150ms
        }
        chip.startAnimation(scaleAnimation)
    }

    private fun ensureDefaultSelection() {
        // Si no hay selección en Status, activar "Todos" automáticamente
        if (!isStatusFilterSelected()) {
            statusFilter = FirewallStatusFilter.ALL
            prefs.setStatusFilter(FirewallStatusFilter.ALL.toPreferenceValue())
            updateStatusChipSelection()
        }

        // Si no hay selección en Type, activar "Apps" automáticamente
        if (!isTypeFilterSelected()) {
            typeFilter = FirewallTypeFilter.ALL
            currentSortFilterMode = SortFilterMode.NAME
            prefs.setTypeFilter(FirewallTypeFilter.ALL.toPreferenceValue())
            updateTypeChipSelection()
        }
    }

    private fun isStatusFilterSelected(): Boolean {
        return binding.chipStatusAll.isChecked || binding.chipStatusAllowed.isChecked || binding.chipStatusBlocked.isChecked
    }

    private fun isTypeFilterSelected(): Boolean {
        return binding.chipTypeAll.isChecked || binding.chipTypeUser.isChecked || binding.chipTypeSystem.isChecked || binding.chipTypeInternet.isChecked
    }

    private fun setupMasterToggle() {
        // El switch está ahora en el header, no en el fragment
        // Solo actualizamos el estado cuando se reanuda el fragment
    }

    private fun applyStatusFilter(filter: FirewallStatusFilter) {
        statusFilter = filter
        prefs.setStatusFilter(filter.toPreferenceValue())
        updateStatusChipSelection()
        sortAndDisplayApps()
    }

    private fun applyTypeFilter(filter: FirewallTypeFilter) {
        typeFilter = filter
        prefs.setTypeFilter(filter.toPreferenceValue())
        currentSortFilterMode = mapTypeFilterToSortMode(filter)
        updateTypeChipSelection()
        sortAndDisplayApps()
    }

    private fun updateStatusChipSelection() {
        isStatusFilterBeingUpdated = true
        try {
            binding.chipStatusAll.isChecked = statusFilter == FirewallStatusFilter.ALL
            binding.chipStatusAllowed.isChecked = statusFilter == FirewallStatusFilter.ALLOWED
            binding.chipStatusBlocked.isChecked = statusFilter == FirewallStatusFilter.BLOCKED
        } finally {
            isStatusFilterBeingUpdated = false
        }
    }

    private fun updateTypeChipSelection() {
        isTypeFilterBeingUpdated = true
        try {
            binding.chipTypeAll.isChecked = typeFilter == FirewallTypeFilter.ALL
            binding.chipTypeUser.isChecked = typeFilter == FirewallTypeFilter.USER
            binding.chipTypeSystem.isChecked = typeFilter == FirewallTypeFilter.SYSTEM
            binding.chipTypeInternet.isChecked = typeFilter == FirewallTypeFilter.INTERNET
        } finally {
            isTypeFilterBeingUpdated = false
        }
    }

    private fun mapTypeFilterToSortMode(filter: FirewallTypeFilter): SortFilterMode {
        return when (filter) {
            FirewallTypeFilter.SYSTEM -> SortFilterMode.SYSTEM
            FirewallTypeFilter.USER -> SortFilterMode.USER
            FirewallTypeFilter.INTERNET -> SortFilterMode.INTERNET_ONLY
            FirewallTypeFilter.ALL -> SortFilterMode.NAME
        }
    }

    private fun mapSortModeToTypeFilter(mode: SortFilterMode): FirewallTypeFilter {
        return when (mode) {
            SortFilterMode.SYSTEM -> FirewallTypeFilter.SYSTEM
            SortFilterMode.USER -> FirewallTypeFilter.USER
            SortFilterMode.INTERNET_ONLY -> FirewallTypeFilter.INTERNET
            SortFilterMode.NAME -> FirewallTypeFilter.ALL
        }
    }

    private fun FirewallStatusFilter.toPreferenceValue(): String {
        return when (this) {
            FirewallStatusFilter.ALL -> "all"
            FirewallStatusFilter.ALLOWED -> "allowed"
            FirewallStatusFilter.BLOCKED -> "blocked"
        }
    }

    private fun FirewallTypeFilter.toPreferenceValue(): String {
        return when (this) {
            FirewallTypeFilter.ALL -> "all"
            FirewallTypeFilter.USER -> "user"
            FirewallTypeFilter.SYSTEM -> "system"
            FirewallTypeFilter.INTERNET -> "internet"
        }
    }

    private fun String.toStatusFilter(): FirewallStatusFilter {
        return when (this) {
            "allowed" -> FirewallStatusFilter.ALLOWED
            "blocked" -> FirewallStatusFilter.BLOCKED
            else -> FirewallStatusFilter.ALL
        }
    }

    private fun String.toTypeFilter(): FirewallTypeFilter {
        return when (this) {
            "user" -> FirewallTypeFilter.USER
            "system" -> FirewallTypeFilter.SYSTEM
            "internet" -> FirewallTypeFilter.INTERNET
            else -> FirewallTypeFilter.ALL
        }
    }

    private fun isVpnServiceRunning(): Boolean {
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (NetStatsFirewallVpnService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun onMasterToggleChanged(isEnabled: Boolean) {
        if (isEnabled) {
            applyAllRules()
        } else {
            removeAllRules()
        }
    }

    private fun applyAllRules() {
        startVpnService()
    }

    private fun removeAllRules() {
        stopVpnService()
    }

    private fun forceVpnRestart() {
        if (!prefs.isFirewallEnabled() || currentMode != NetStatsFirewallMode.VPN) {
            return
        }

        // Detener VPN actual
        stopVpnService()

        // Reiniciar con delay mínimo para asegurar que se detuvo completamente
        lifecycleScope.launch {
            delay(100) // Delay de 100ms para minimizar ventana de vulnerabilidad
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            onActivityResult(vpnRequestCode, Activity.RESULT_OK, null)
        }
    }

    private fun stopVpnService() {
        NetStatsFirewallVpnService.stopVpn(requireContext())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == Activity.RESULT_OK) {
            val intent = Intent(requireContext(), NetStatsFirewallVpnService::class.java)
            requireContext().startService(intent)
        }
    }

    private fun loadApps() {
        if (!isAdded) return
        
        actionMode?.finish()
        
        // Mostrar loading siempre al inicio
        loadingContainer.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        binding.emptyStateContainer.visibility = View.GONE

        lifecycleScope.launch {
            if (!isAdded) return@launch
            
            // Intentar cargar caché primero para mostrar algo rápido
            val cachedAppsWithIcons = withContext(Dispatchers.IO) {
                if (!isAdded) return@withContext emptyList<AppInfo>()
                cacheManager.getAllAppsFromCache()
            }

            if (isAdded && cachedAppsWithIcons.isNotEmpty()) {
                // Mostrar caché inmediatamente
                masterAppList.clear()
                masterAppList.addAll(cachedAppsWithIcons.map { app ->
                    app.copy(
                        isWifiBlocked = prefs.isWifiBlocked(currentMode, app.packageName),
                        isDataBlocked = prefs.isDataBlocked(currentMode, app.packageName)
                    )
                })
                sortAndDisplayApps()
                loadingContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                appAdapter.notifyDataSetChanged()
            }

            // Cargar apps completo en background
            val systemApps = withContext(Dispatchers.IO) {
                if (!isAdded) return@withContext emptyList<AppInfo>()

                // Guardar el contexto de aplicación que tiene un ciclo de vida más largo
                val appContext = requireContext().applicationContext
                val packageManager = appContext.packageManager
                val packages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                val apps = mutableListOf<AppInfo>()

                for (pkgInfo in packages) {
                    if (!isAdded) break

                    val app = pkgInfo.applicationInfo
                    if (app == null) continue

                    val appName = packageManager.getApplicationLabel(app).toString()
                    val appIcon = packageManager.getApplicationIcon(app)
                    val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val hasInternet = pkgInfo.requestedPermissions?.contains("android.permission.INTERNET") == true

                    // Usar el contexto de aplicación para operaciones que no requieren contexto de fragmento
                    val (downloadBytes, uploadBytes, totalBytes) = NetworkUtils.getAppDataUsageForUid(
                        appContext,
                        app.uid,
                        NetworkUtils.TimePeriod.MONTHLY
                    )

                    apps.add(AppInfo(
                        appName = appName,
                        packageName = app.packageName,
                        appIcon = appIcon,
                        isSystemApp = isSystemApp,
                        hasInternetPermission = hasInternet,
                        isWifiBlocked = prefs.isWifiBlocked(currentMode, app.packageName),
                        isDataBlocked = prefs.isDataBlocked(currentMode, app.packageName),
                        isSelected = false,
                        downloadBytes = downloadBytes,
                        uploadBytes = uploadBytes,
                        totalBytes = totalBytes
                    ))
                }

                apps
            }

            if (!isAdded || systemApps.isEmpty()) return@launch
            
            // Guardar en caché
            withContext(Dispatchers.IO) {
                if (!isAdded) return@withContext
                cacheManager.saveAppsToCache(systemApps)
            }

            // Mostrar resultado final y ocultar loading
            if (isAdded) {
                masterAppList.clear()
                masterAppList.addAll(systemApps)
                sortAndDisplayApps()
                loadingContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                binding.emptyStateContainer.visibility = View.GONE
                appAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun sortAndDisplayApps() {
        var processedList = masterAppList.toList()

        // Aplicar búsqueda
        currentSearchQuery?.let { query ->
            if (query.isNotBlank()) {
                processedList = processedList.filter {
                    it.appName.contains(query, ignoreCase = true)
                }
            }
        }

        // Aplicar filtro de Estado (Status)
        processedList = when (statusFilter) {
            FirewallStatusFilter.ALL -> processedList
            FirewallStatusFilter.ALLOWED -> processedList.filter { !it.isBlocked }
            FirewallStatusFilter.BLOCKED -> processedList.filter { it.isBlocked }
        }

        // Aplicar filtro de Tipo (Type) - Combinado con Estado
        processedList = when (currentSortFilterMode) {
            SortFilterMode.SYSTEM -> processedList.filter { it.isSystemApp }
            SortFilterMode.USER -> processedList.filter { !it.isSystemApp }
            SortFilterMode.INTERNET_ONLY -> processedList.filter { it.hasInternetPermission }
            SortFilterMode.NAME -> processedList
        }

        // Aplicar ordenamiento
        val sortedList: List<AppInfo>
        if (isSortBlockedFirst) {
            sortedList = processedList.sortedWith(compareBy(
                { !it.isBlocked },
                { it.appName.lowercase() }
            ))
        } else {
            sortedList = processedList.sortedBy { it.appName.lowercase() }
        }

        // Actualizar adapter con animación de fade
        animateListUpdate(sortedList)
    }

    private fun animateListUpdate(newList: List<AppInfo>) {
        val emptyStateContainer = binding.emptyStateContainer
        
        // Reseteár scroll hacia arriba cuando cambia la lista
        recyclerView.scrollToPosition(0)
        
        // Si la lista está vacía y NO estamos cargando, mostrar el empty state
        if (newList.isEmpty() && !isLoading) {
            emptyStateContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else if (newList.isNotEmpty()) {
            // Si hay items, mostrar el RecyclerView y ocultar el empty state
            emptyStateContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            
            // Actualizar la lista - DiffUtil + ItemAnimator harán las animaciones
            appAdapter.updateApps(newList)
        }
    }

    private fun onToggleClicked(app: AppInfo, shouldBlock: Boolean) {
        var targetApps = if (isInSelectionMode) {
            masterAppList.filter { it.isSelected }
        } else {
            listOf(app)
        }

        if (targetApps.isEmpty() && !isInSelectionMode) {
            targetApps = listOf(app)
        }

        // Cambiar el estado en los objetos Y en prefs (synchronously)
        for (targetApp in targetApps) {
            prefs.setWifiBlocked(currentMode, targetApp.packageName, shouldBlock)
            prefs.setDataBlocked(currentMode, targetApp.packageName, shouldBlock)

            targetApp.isWifiBlocked = shouldBlock
            targetApp.isDataBlocked = shouldBlock
        }

        // Actualizar en caché en background (sin esperar)
        lifecycleScope.launch {
            for (targetApp in targetApps) {
                cacheManager.updateAppInCache(targetApp)
            }
        }

        // Reiniciar VPN si el firewall está habilitado para aplicar cambios inmediatamente
        if (prefs.isFirewallEnabled() && currentMode == NetStatsFirewallMode.VPN) {
            forceVpnRestart()
        }

        // Actualizar la UI inmediatamente con el nuevo estado
        // Usar post() para asegurar que se ejecuta después de que el handler procese todos los eventos
        recyclerView.post {
            if (isInSelectionMode) {
                actionMode?.finish()
            }
            
            // Recalcular la lista con los filtros actuales
            sortAndDisplayApps()
            
            // Forzar actualización de los items modificados explícitamente
            // Esto garantiza que el texto de estado se actualice aunque el filtro lo oculte temporalmente
            for (targetApp in targetApps) {
                val visibleApps = appAdapter.getAppList()
                val index = visibleApps.indexOfFirst { it.packageName == targetApp.packageName }
                if (index != -1) {
                    appAdapter.notifyItemChanged(index)
                }
            }
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            isInSelectionMode = true
            mode?.menuInflater?.inflate(R.menu.selection_menu, menu)
            mode?.title = getString(R.string.selection_title_zero)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.menu_select_all -> {
                    selectAllApps()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            isInSelectionMode = false
            actionMode = null
            masterAppList.forEach { it.isSelected = false }
            sortAndDisplayApps()
        }
    }

    private fun toggleSelection(app: AppInfo) {
        app.isSelected = !app.isSelected

        val selectedCount = masterAppList.count { it.isSelected }

        if (selectedCount == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = when (selectedCount) {
                1 -> getString(R.string.selection_title_one)
                else -> getString(R.string.selection_title_many, selectedCount)
            }
        }

        val index = appAdapter.getAppList().indexOf(app)
        if (index != -1) {
            appAdapter.notifyItemChanged(index)
        }
    }

    private fun selectAllApps() {
        val visibleApps = appAdapter.getAppList()
        val allSelected = visibleApps.all { it.isSelected }

        visibleApps.forEach { app ->
            app.isSelected = !allSelected
        }

        val selectedCount = masterAppList.count { it.isSelected }
        actionMode?.title = getString(R.string.selection_title_many, selectedCount)
        appAdapter.updateApps(visibleApps)
    }

    private fun exportSettings(uri: Uri) {
        if (!isAdded) return
        
        lifecycleScope.launchWhenStarted {
            withContext(Dispatchers.IO) {
                if (!isAdded) return@withContext
                
                try {
                    val json = prefs.exportAllSettings()
                    if (json == null) {
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                        }
                        return@withContext
                    }

                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                        out.flush()
                    }

                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        Toast.makeText(requireContext(), getString(R.string.export_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("FirewallFragment", "Export failed", e)
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun importSettings(uri: Uri) {
        if (!isAdded) return
        
        lifecycleScope.launchWhenStarted {
            var importSuccess = false
            try {
                val jsonString = requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).readText()
                }

                if (jsonString.isNullOrBlank()) {
                    throw Exception("File is empty or could not be read.")
                }

                importSuccess = prefs.importAllSettings(jsonString)

            } catch (e: Exception) {
                Log.e("FirewallFragment", "Import failed", e)
                importSuccess = false
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                
                if (importSuccess) {
                    Toast.makeText(requireContext(), getString(R.string.import_success), Toast.LENGTH_SHORT).show()

                    loadApps()
                    if (prefs.isFirewallEnabled()) {
                        if (currentMode == NetStatsFirewallMode.VPN) {
                            forceVpnRestart()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.firewall_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val reminderItem = menu.findItem(R.id.menu_reboot_reminder)
        reminderItem?.isChecked = prefs.isRebootReminderEnabled()
        super.onPrepareOptionsMenu(menu)
    }

}