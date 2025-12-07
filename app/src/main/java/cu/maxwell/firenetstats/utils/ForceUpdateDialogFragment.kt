package cu.maxwell.firenetstats.utils

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import cu.maxwell.firenetstats.R

class ForceUpdateDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "ForceUpdateDialog"
        private const val ARG_MESSAGE = "arg_message"
        private const val ARG_UPDATE_URL = "arg_update_url"
        private const val ARG_CURRENT_VERSION = "arg_current_version"

        fun newInstance(message: String, updateUrl: String, currentVersion: String): ForceUpdateDialogFragment {
            val fragment = ForceUpdateDialogFragment()
            val args = Bundle()
            args.putString(ARG_MESSAGE, message)
            args.putString(ARG_UPDATE_URL, updateUrl)
            args.putString(ARG_CURRENT_VERSION, currentVersion)
            fragment.arguments = args
            fragment.isCancelable = false   // Bloqueo total
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_FireNetStats_ForceUpdateDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return inflater.inflate(R.layout.dialog_force_update, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val message = arguments?.getString(ARG_MESSAGE) ?: "Actualización requerida"
        val updateUrl = arguments?.getString(ARG_UPDATE_URL) ?: ""
        val currentVersion = arguments?.getString(ARG_CURRENT_VERSION) ?: "desconocida"

        view.findViewById<TextView>(R.id.tvCurrentVersion)?.text = currentVersion
        view.findViewById<TextView>(R.id.tvForceUpdateMessage)?.text = message

        view.findViewById<View>(R.id.btnForceDownload)?.setOnClickListener {
            openUpdateUrl(updateUrl)
        }

        view.findViewById<View>(R.id.btnForceClose)?.setOnClickListener {
            requireActivity().finishAffinity()
        }
    }

    private fun openUpdateUrl(updateUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo la URL: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        // No permitir cancelación
    }
}
