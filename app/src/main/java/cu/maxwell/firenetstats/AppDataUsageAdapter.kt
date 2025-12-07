package cu.maxwell.firenetstats

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cu.maxwell.firenetstats.utils.NetworkUtils

class AppDataUsageAdapter : RecyclerView.Adapter<AppDataUsageAdapter.AppDataUsageViewHolder>() {

    private var appDataList: List<NetworkUtils.AppDataUsage> = emptyList()

    fun updateData(newData: List<NetworkUtils.AppDataUsage>) {
        appDataList = newData
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppDataUsageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_data_usage, parent, false)
        return AppDataUsageViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppDataUsageViewHolder, position: Int) {
        holder.bind(appDataList[position])
    }

    override fun getItemCount(): Int = appDataList.size

    class AppDataUsageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        private val tvDataUsage: TextView = itemView.findViewById(R.id.tvDataUsage)

        fun bind(appData: NetworkUtils.AppDataUsage) {
            // Configurar icono
            ivAppIcon.setImageDrawable(appData.icon)

            // Configurar nombre
            tvAppName.text = appData.appName

            // Configurar nombre del paquete
            tvPackageName.text = appData.packageName

            // Configurar consumo con colores según el tamaño
            tvDataUsage.text = appData.formattedUsage

            // Aplicar colores según consumo
            val context = itemView.context
            val colorRes = when {
                appData.totalBytes < 50 * 1024 * 1024 -> R.color.good_connection // Verde para < 50MB
                appData.totalBytes < 200 * 1024 * 1024 -> R.color.medium_connection // Amarillo para 50-200MB
                else -> R.color.poor_connection // Rojo para > 200MB
            }
            tvDataUsage.setTextColor(ContextCompat.getColor(context, colorRes))
        }
    }
}