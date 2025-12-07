package cu.maxwell.firenetstats.firewall

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cu.maxwell.firenetstats.R
import cu.maxwell.firenetstats.database.AppCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InterceptDialogActivity : AppCompatActivity() {

    private val cacheManager by lazy { AppCacheManager(this) }
    private val prefs by lazy { NetStatsFirewallPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("package_name") ?: return
        val appName = intent.getStringExtra("app_name") ?: return

        val packageManager = packageManager
        val appIcon = try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        // Crear diálogo personalizado con Material Design 3
        val view = layoutInflater.inflate(R.layout.dialog_intercept, null)
        val iconView = view.findViewById<ImageView>(R.id.dialog_app_icon)
        val nameView = view.findViewById<TextView>(R.id.dialog_app_name)
        //val permissionsView = view.findViewById<TextView>(R.id.dialog_permissions)
        val allowBtn = view.findViewById<MaterialButton>(R.id.btn_allow)
        val blockBtn = view.findViewById<MaterialButton>(R.id.btn_block)

        // Configurar ícono
        appIcon?.let {
            iconView.setImageDrawable(it)
            // Animar entrada del ícono
            iconView.scaleX = 0.8f
            iconView.scaleY = 0.8f
            iconView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start()
        }

        nameView.text = appName

        // Obtener permisos de la app
        //val permissions = getAppPermissions(packageName)
        //permissionsView.text = permissions

        val dialog = MaterialAlertDialogBuilder(this, R.style.AppDialogTheme)
            .setView(view)
            .setCancelable(false)
            .create()

        // Quitar fondo opaco detrás del layout
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Animación al aparecer
        dialog.setOnShowListener {
            animateDialogEntry(view)
        }

        allowBtn.setOnClickListener {
            animateButtonClick(allowBtn) {
                handleAllow(packageName)
                dialog.dismiss()
                finish()
            }
        }

        blockBtn.setOnClickListener {
            animateButtonClick(blockBtn) {
                handleBlock(packageName)
                dialog.dismiss()
                finish()
            }
        }

        dialog.show()
    }

    /*private fun getAppPermissions(packageName: String): String {
        return try {
            val packageManager = packageManager
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS
            )

            val permissions = mutableListOf<String>()

            if (packageInfo.requestedPermissions?.contains("android.permission.INTERNET") == true) {
                permissions.add("• Acceso a internet")
            }
            if (packageInfo.requestedPermissions?.contains("android.permission.ACCESS_NETWORK_STATE") == true) {
                permissions.add("• Estado de red")
            }
            if (packageInfo.requestedPermissions?.contains("android.permission.ACCESS_WIFI_STATE") == true) {
                permissions.add("• Estado de WiFi")
            }
            if (packageInfo.requestedPermissions?.contains("android.permission.ACCESS_COARSE_LOCATION") == true ||
                packageInfo.requestedPermissions?.contains("android.permission.ACCESS_FINE_LOCATION") == true
            ) {
                permissions.add("• Ubicación")
            }

            if (permissions.isEmpty()) {
                permissions.add("• Acceso a datos de red")
            }

            permissions.joinToString("\n")
        } catch (e: Exception) {
            "• Acceso a datos de red\n• Datos móviles\n• WiFi"
        }
    }*/

    private fun animateDialogEntry(view: View) {
        view.alpha = 0f
        view.scaleX = 0.95f
        view.scaleY = 0.95f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .start()
    }

    private fun animateButtonClick(button: MaterialButton, onAnimationEnd: () -> Unit) {
        button.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                button.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .withEndAction(onAnimationEnd)
                    .start()
            }
            .start()
    }

    private fun handleAllow(packageName: String) {
        prefs.setWifiBlocked(NetStatsFirewallMode.VPN, packageName, false)
        prefs.setDataBlocked(NetStatsFirewallMode.VPN, packageName, false)
        Log.d("InterceptDialogActivity", "Allowed app: $packageName")

        lifecycleScope.launch(Dispatchers.IO) {
            cacheManager.updateBlockedState(packageName, false)
        }

        // Pedir al servicio que refresque la configuración (aplique nuevos prefs)
        val refreshIntent = Intent(this, NetStatsFirewallVpnService::class.java).apply {
            action = NetStatsFirewallVpnService.ACTION_REFRESH
        }
        startService(refreshIntent)
        Log.d("InterceptDialogActivity", "Sent refresh command to VPN service")

        NetStatsFirewallVpnService.sendRulesUpdatedBroadcast(this, packageName, false)

        // Limpiar la marca de notificación en sesión para permitir re-notificar si intenta de nuevo
        val clearIntent = Intent(this, NetStatsFirewallVpnService::class.java).apply {
            action = NetStatsFirewallVpnService.ACTION_CLEAR_NOTIFIED
            putExtra("package_name", packageName)
        }
        startService(clearIntent)
        Log.d("InterceptDialogActivity", "Sent clear notified command to VPN service")
    }

    private fun handleBlock(packageName: String) {
        prefs.setWifiBlocked(NetStatsFirewallMode.VPN, packageName, true)
        prefs.setDataBlocked(NetStatsFirewallMode.VPN, packageName, true)
        Log.d("InterceptDialogActivity", "Blocked app: $packageName")

        lifecycleScope.launch(Dispatchers.IO) {
            cacheManager.updateBlockedState(packageName, true)
        }

        NetStatsFirewallVpnService.sendRulesUpdatedBroadcast(this, packageName, true)
    }
}
