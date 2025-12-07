package cu.maxwell.firenetstats.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

object ForceUpdateRepository {

    private const val TAG = "ForceUpdateRepository"

    private const val BASE_URL =
        "https://gist.githubusercontent.com/MaxCode93/c93f8ed9f5533fadffb2f10f4e856602/raw/FORCE_UPDATE.fns"

    private fun getFreshUrl(): String {
        return "$BASE_URL?cb=${System.currentTimeMillis()}"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun fetchForceUpdateInfo(): ForceUpdateInfo? {
        return try {
            val request = Request.Builder()
                .url(getFreshUrl())
                .addHeader("Accept", "application/vnd.github.v3.raw")
                .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .addHeader("Pragma", "no-cache")
                .addHeader("Expires", "0")
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful && response.body != null) {
                val jsonString = response.body!!.string()
                Log.d(TAG, "Archivo FORCE_UPDATE descargado exitosamente")
                
                val gson = Gson()
                val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
                
                // Parsear blocked_versions: puede ser un array o la cadena "all"
                val blockedVersionsList = mutableListOf<String>()
                val blockedVersionsValue = jsonObject.get("blocked_versions")
                
                if (blockedVersionsValue?.isJsonPrimitive == true && blockedVersionsValue.asString == "all") {
                    // Si es "all", agregar la cadena especial
                    blockedVersionsList.add("all")
                } else if (blockedVersionsValue?.isJsonArray == true) {
                    // Si es un array, parsear versiones normales
                    blockedVersionsList.addAll(
                        jsonObject.getAsJsonArray("blocked_versions")
                            ?.map { it.asString }
                            ?.toList() ?: emptyList()
                    )
                }
                
                ForceUpdateInfo(
                    forceUpdate = jsonObject.get("force_update").asBoolean,
                    blockedVersions = blockedVersionsList,
                    minRequiredVersion = jsonObject.get("min_required_version")?.asString ?: "",
                    message = jsonObject.get("message")?.asString ?: "Actualización requerida",
                    updateUrl = jsonObject.get("update_url")?.asString ?: "",
                    lastUpdated = jsonObject.get("last_updated")?.asString ?: ""
                )
            } else {
                Log.w(TAG, "Error descargando FORCE_UPDATE: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción descargando FORCE_UPDATE: ${e.message}", e)
            null
        }
    }

    data class ForceUpdateInfo(
        val forceUpdate: Boolean,
        val blockedVersions: List<String>,
        val minRequiredVersion: String,
        val message: String,
        val updateUrl: String,
        val lastUpdated: String
    )
}
