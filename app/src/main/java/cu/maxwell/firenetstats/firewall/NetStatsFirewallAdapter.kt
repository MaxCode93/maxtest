package cu.maxwell.firenetstats.firewall
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import cu.maxwell.firenetstats.R

class NetStatsFirewallAdapter(
    private var appList: List<AppInfo>,
    val onItemClick: (AppInfo) -> Unit,
    val onItemLongClick: (AppInfo) -> Unit,
    val onToggleClick: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<NetStatsFirewallAdapter.AppViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_firewall, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = appList[position]
        holder.bind(appInfo)
        // holder.itemView.isActivated = appInfo.isSelected
    }

    override fun getItemCount(): Int = appList.size

    fun getAppList(): List<AppInfo> {
        return appList
    }

    fun updateApps(newAppList: List<AppInfo>) {
        val diffCallback = AppDiffCallback(appList, newAppList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.appList = newAppList
        diffResult.dispatchUpdatesTo(this)
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardContainer: MaterialCardView = itemView.findViewById(R.id.card_app)
        private val appIcon: ImageView = itemView.findViewById(R.id.image_app_icon)
        private val appName: TextView = itemView.findViewById(R.id.text_app_name)
        private val statusText: TextView? = itemView.findViewById(R.id.text_status)
        private val blockFab: FloatingActionButton = itemView.findViewById(R.id.switch_block)

        // Color cache
        private val colorPrimary = ContextCompat.getColor(itemView.context, R.color.primary_color)
        private val colorGrey = ContextCompat.getColor(itemView.context, R.color.icon_grey_disabled)
        private val colorSurface = ContextCompat.getColor(itemView.context, R.color.card_background)
        private val colorSelected = ContextCompat.getColor(itemView.context, R.color.selected_grey)
        private val colorBlocked = ContextCompat.getColor(itemView.context, R.color.firewall_red)
        private val colorAllowed = ContextCompat.getColor(itemView.context, R.color.firewall_green)


        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(appList[position])
                }
            }
            // Long-press deshabilitado por ahora
        }

        fun bind(appInfo: AppInfo) {
            appIcon.setImageDrawable(appInfo.appIcon)
            appName.text = appInfo.appName

            // Format and display data consumption
            val downloadText: TextView? = itemView.findViewById(R.id.text_download)
            val uploadText: TextView? = itemView.findViewById(R.id.text_upload)
            val totalText: TextView? = itemView.findViewById(R.id.text_total)

            downloadText?.text = "↓ ${formatBytes(appInfo.downloadBytes)}"
            uploadText?.text = "↑ ${formatBytes(appInfo.uploadBytes)}"
            totalText?.text = "Total: ${formatBytes(appInfo.totalBytes)}"

            blockFab.setOnClickListener(null)
            blockFab.tag = appInfo.isBlocked

            if (appInfo.isBlocked) {
                blockFab.setImageDrawable(ContextCompat.getDrawable(itemView.context, R.drawable.ic_lock_open))
                blockFab.setImageTintList(ContextCompat.getColorStateList(itemView.context, R.color.white))
                blockFab.backgroundTintList = ContextCompat.getColorStateList(itemView.context, R.color.good_connection)
            } else {
                blockFab.setImageDrawable(ContextCompat.getDrawable(itemView.context, R.drawable.ic_lock))
                blockFab.setImageTintList(ContextCompat.getColorStateList(itemView.context, R.color.white))
                blockFab.backgroundTintList = ContextCompat.getColorStateList(itemView.context, R.color.firewall_red)
            }

            if (appInfo.isSelected) {
                cardContainer.strokeColor = colorPrimary
                cardContainer.setCardBackgroundColor(colorSelected)
            } else {
                cardContainer.strokeColor = colorGrey
                cardContainer.setCardBackgroundColor(colorSurface)
            }

            // Set status text and color
            val statusColor = if (appInfo.isBlocked) colorBlocked else colorAllowed
            statusText?.text = itemView.context.getString(
                if (appInfo.isBlocked) R.string.firewall_status_blocked else R.string.firewall_status_allowed
            )
            statusText?.setTextColor(statusColor)

            blockFab.setOnClickListener { _ ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val currentBlocked = appList[position].isBlocked
                    val newBlockedState = !currentBlocked
                    
                    // Animate FAB with scale effect
                    val scaleDown = ObjectAnimator.ofFloat(blockFab, "scaleX", 1f, 0.8f)
                    scaleDown.duration = 100
                    val scaleDownY = ObjectAnimator.ofFloat(blockFab, "scaleY", 1f, 0.8f)
                    scaleDownY.duration = 100
                    scaleDown.start()
                    scaleDownY.start()
                    
                    // Update icon and color after animation starts
                    blockFab.postDelayed({
                        if (newBlockedState) {
                            blockFab.setImageDrawable(ContextCompat.getDrawable(itemView.context, R.drawable.ic_lock_open))
                            blockFab.setImageTintList(ContextCompat.getColorStateList(itemView.context, R.color.white))
                            blockFab.backgroundTintList = ContextCompat.getColorStateList(itemView.context, R.color.good_connection)
                        } else {
                            blockFab.setImageDrawable(ContextCompat.getDrawable(itemView.context, R.drawable.ic_lock))
                            blockFab.setImageTintList(ContextCompat.getColorStateList(itemView.context, R.color.white))
                            blockFab.backgroundTintList = ContextCompat.getColorStateList(itemView.context, R.color.firewall_red)
                        }
                        
                        // Scale back up
                        val scaleUp = ObjectAnimator.ofFloat(blockFab, "scaleX", 0.8f, 1f)
                        scaleUp.duration = 100
                        val scaleUpY = ObjectAnimator.ofFloat(blockFab, "scaleY", 0.8f, 1f)
                        scaleUpY.duration = 100
                        scaleUp.start()
                        scaleUpY.start()
                    }, 50)
                    
                    onToggleClick(appList[position], newBlockedState)
                }
            }
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val k = 1024L
            val sizes = arrayOf("B", "KB", "MB", "GB")
            val i = (Math.log(bytes.toDouble()) / Math.log(k.toDouble())).toInt()
            return if (i >= sizes.size) {
                String.format("%.1f %s", bytes.toDouble() / Math.pow(k.toDouble(), i.toDouble()), sizes[sizes.size - 1])
            } else {
                String.format("%.1f %s", bytes.toDouble() / Math.pow(k.toDouble(), i.toDouble()), sizes[i])
            }
        }
    }
}

// DiffUtil Callback para animaciones suaves al actualizar la lista
class AppDiffCallback(
    private val oldList: List<AppInfo>,
    private val newList: List<AppInfo>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        // Dos items son el mismo si tienen el mismo packageName
        return oldList[oldItemPosition].packageName == newList[newItemPosition].packageName
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        // El contenido es igual si todos los campos relevantes son iguales
        val oldApp = oldList[oldItemPosition]
        val newApp = newList[newItemPosition]
        return oldApp.appName == newApp.appName &&
                oldApp.isWifiBlocked == newApp.isWifiBlocked &&
                oldApp.isDataBlocked == newApp.isDataBlocked &&
                oldApp.isSelected == newApp.isSelected &&
                oldApp.downloadBytes == newApp.downloadBytes &&
                oldApp.uploadBytes == newApp.uploadBytes
    }
}