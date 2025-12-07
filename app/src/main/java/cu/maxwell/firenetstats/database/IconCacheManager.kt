package cu.maxwell.firenetstats.database

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IconCacheManager(private val context: Context) {

    private val cacheDir = File(context.cacheDir, "app_icons")
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    suspend fun saveIcon(packageName: String, drawable: Drawable?) {
        if (drawable == null) {
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                val file = File(cacheDir, sanitizeFileName(packageName) + ".png")
                
                // Convertir Drawable a Bitmap de forma segura
                val bitmap = drawableToBitmap(drawable)
                
                // Guardar bitmap comprimido
                file.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
                    output.flush()
                }
                
                bitmap.recycle()
            } catch (e: Exception) {
                // Error guardando icono
            }
        }
    }

    suspend fun getIcon(packageName: String): Drawable? {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(cacheDir, sanitizeFileName(packageName) + ".png")
                if (file.exists() && file.length() > 0) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun clearAllIcons() {
        withContext(Dispatchers.IO) {
            try {
                if (cacheDir.exists()) {
                    cacheDir.deleteRecursively()
                    cacheDir.mkdirs()
                }
            } catch (e: Exception) {
                // Error borrando caché
            }
        }
    }

    suspend fun deleteIcon(packageName: String) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(cacheDir, sanitizeFileName(packageName) + ".png")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                // Error borrando icono
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        
        val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        
        return bitmap
    }

    private fun sanitizeFileName(packageName: String): String {
        return packageName.replace("/", "_").replace("\\", "_").replace(":", "_")
    }
}


