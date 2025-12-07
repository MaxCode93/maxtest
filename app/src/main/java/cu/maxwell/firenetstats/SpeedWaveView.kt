package cu.maxwell.firenetstats

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

class SpeedWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chartPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chartFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    enum class MeterType {
        DOWNLOAD,
        UPLOAD
    }

    private var meterType: MeterType = MeterType.DOWNLOAD

    // Paleta dinámica según tipo + tema
    private var containerColor: Int = 0
    private var chartBaseColor: Int = 0

    // Valores actuales / mostrados
    private var rawSpeedValue = 0f      // en bytes/s
    private var displaySpeed = 0f       // valor formateado para mostrar
    private var displayUnit = "KB/s"
    private var label = "SPEED"

    // Historial para el mini-chart
    private val historyCapacity = 40
    private val speedHistory = FloatArray(historyCapacity) { 0f } // valores normalizados 0..1
    private var historySize = 0

    private var animator: ValueAnimator? = null

    private var maxSpeed = 0f
    private var showMax = false

    init {
        setupPaints()
    }

    // ------------------ Tema / paleta ------------------

    private fun isDarkTheme(): Boolean {
        val uiMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun refreshPalette() {
        val dark = isDarkTheme()

        val (containerRes, chartRes) = when (meterType) {
            MeterType.DOWNLOAD ->
                if (dark) {
                    R.color.speed_download_container_dark to R.color.speed_download_wave_dark
                } else {
                    R.color.speed_download_container_light to R.color.speed_download_wave_light
                }

            MeterType.UPLOAD ->
                if (dark) {
                    R.color.speed_upload_container_dark to R.color.speed_upload_wave_dark
                } else {
                    R.color.speed_upload_container_light to R.color.speed_upload_wave_light
                }
        }

        containerColor = ContextCompat.getColor(context, containerRes)
        chartBaseColor = ContextCompat.getColor(context, chartRes)
    }

    private fun setupPaints() {
        refreshPalette()

        backgroundPaint.apply {
            style = Paint.Style.FILL
            color = containerColor
            setShadowLayer(10f, 0f, 6f, Color.argb(80, 0, 0, 0))
        }

        val dark = isDarkTheme()
        borderPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = if (dark) {
                Color.argb(90, 255, 255, 255)
            } else {
                Color.argb(35, 0, 0, 0)
            }
        }

        chartPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = chartBaseColor
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        chartFillPaint.apply {
            style = Paint.Style.FILL
            // Versión suavizada del color base
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(chartBaseColor, hsl)
            hsl[2] = (hsl[2] * 1.15f).coerceIn(0f, 1f)
            val softColor = ColorUtils.HSLToColor(hsl)
            color = ColorUtils.setAlphaComponent(softColor, 55)
            isAntiAlias = true
        }

        textPaint.isAntiAlias = true
    }

    // ------------------ API pública ------------------

    fun setSpeed(speedBytesPerSecond: Float, animate: Boolean = true) {
        val oldDisplaySpeed = displaySpeed

        rawSpeedValue = speedBytesPerSecond
        val (formattedSpeed, unit) = formatSpeed(speedBytesPerSecond)
        displayUnit = unit

        // Normalizamos la velocidad tomando 1 MB/s como referencia base para el chart
        val normalized = (speedBytesPerSecond / 1_000_000f).coerceIn(0f, 1f)
        addToHistory(normalized)

        if (speedBytesPerSecond > maxSpeed) {
            maxSpeed = speedBytesPerSecond
        }

        if (animate) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(oldDisplaySpeed, formattedSpeed).apply {
                duration = 600
                addUpdateListener { anim ->
                    displaySpeed = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            displaySpeed = formattedSpeed
            invalidate()
        }
    }

    fun setLabel(label: String) {
        this.label = label
        invalidate()
    }

    fun setMeterType(type: MeterType) {
        meterType = type

        // Si el label no fue personalizado, le damos uno por defecto
        if (label == "SPEED") {
            label = when (type) {
                MeterType.DOWNLOAD -> "Download"
                MeterType.UPLOAD -> "Upload"
            }
        }

        refreshPalette()
        setupPaints()
        invalidate()
    }

    fun setMaxSpeed(maxSpeed: Float) {
        this.maxSpeed = maxSpeed
        this.showMax = true
        invalidate()
    }

    // ------------------ Lógica de historial ------------------

    private fun addToHistory(value: Float) {
        if (historySize < historyCapacity) {
            speedHistory[historySize] = value
            historySize++
        } else {
            // Corrimiento simple hacia la izquierda
            for (i in 0 until historyCapacity - 1) {
                speedHistory[i] = speedHistory[i + 1]
            }
            speedHistory[historyCapacity - 1] = value
        }
    }

    private fun formatSpeed(bytesPerSecond: Float): Pair<Float, String> {
        return when {
            bytesPerSecond >= 1_000_000_000f -> Pair(bytesPerSecond / 1_000_000_000f, "GB/s")
            bytesPerSecond >= 1_000_000f     -> Pair(bytesPerSecond / 1_000_000f, "MB/s")
            else                             -> Pair(bytesPerSecond / 1_000f, "KB/s")
        }
    }

    // ------------------ Dibujo ------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        val radius = h * 0.26f
        val padding = 12f

        val bgRect = RectF(
            padding,
            padding,
            w - padding,
            h - padding
        )

        // Fondo
        canvas.drawRoundRect(bgRect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(bgRect, radius, radius, borderPaint)

        val isDark = isDarkTheme()
        val primaryTextColor =
            if (isDark) Color.WHITE else ContextCompat.getColor(context, R.color.text_primary)
        val secondaryTextColor =
            if (isDark) Color.argb(220, 176, 190, 197) else ContextCompat.getColor(context, R.color.text_secondary)
        val tertiaryTextColor =
            if (isDark) Color.argb(180, 144, 164, 174) else Color.argb(150, 117, 117, 117)
        val maxTextColor =
            if (isDark) Color.argb(200, 255, 213, 79) else Color.argb(210, 251, 192, 45)

        val left = bgRect.left + 24f
        val right = bgRect.right - 24f
        val top = bgRect.top + 20f
        val midY = top + h * 0.22f
        val chartTop = midY + h * 0.10f
        val chartBottom = bgRect.bottom - 24f

        // Label (arriba, a la izquierda)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = tertiaryTextColor
        textPaint.textSize = h * 0.12f
        canvas.drawText(label.uppercase(), left, top, textPaint)

        // Velocidad principal
        val speedText = String.format("%.1f", displaySpeed)
        textPaint.color = primaryTextColor
        textPaint.textSize = h * 0.26f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        canvas.drawText(speedText, left, midY, textPaint)

        // Unidad
        textPaint.color = secondaryTextColor
        textPaint.textSize = h * 0.13f
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val unitX = left + textPaint.measureText(speedText) + 12f
        canvas.drawText(displayUnit, unitX, midY, textPaint)

        // Máximo opcional (arriba derecha)
        if (showMax && maxSpeed > 0f) {
            val (maxFormatted, maxUnit) = formatSpeed(maxSpeed)
            textPaint.color = maxTextColor
            textPaint.textSize = h * 0.09f
            textPaint.textAlign = Paint.Align.RIGHT
            val maxText = "Máx ${String.format("%.1f", maxFormatted)} $maxUnit"
            canvas.drawText(maxText, right, top, textPaint)
        }

        // Mini-chart (parte inferior)
        if (historySize > 1) {
            val chartHeight = chartBottom - chartTop
            val stepX = (right - left) / (historyCapacity - 1).coerceAtLeast(1)

            val linePath = Path()
            val fillPath = Path()

            // Primer punto
            val firstValue = speedHistory[0]
            var x = left
            var y = chartBottom - firstValue * chartHeight
            linePath.moveTo(x, y)
            fillPath.moveTo(x, chartBottom)
            fillPath.lineTo(x, y)

            for (i in 1 until historySize) {
                x = left + stepX * i
                val v = speedHistory[i]
                y = chartBottom - v * chartHeight
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            // Cerrar relleno hasta la base
            fillPath.lineTo(x, chartBottom)
            fillPath.close()

            // Dibujar relleno suave primero
            canvas.drawPath(fillPath, chartFillPaint)

            // Dibujar línea principal encima
            canvas.drawPath(linePath, chartPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
