package cu.maxwell.firenetstats

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import cu.maxwell.firenetstats.databinding.SpeedTileBinding
import androidx.core.graphics.toColorInt

class SpeedTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
): LinearLayout(context, attrs, defStyleAttr) {

    private val binding = SpeedTileBinding.inflate(LayoutInflater.from(context), this, true)
	private var baseColor = 0
	private var lastAnimatedColor: Int = Color.WHITE

	fun setMeterType(type: SpeedWaveView.MeterType) {

		if (type == SpeedWaveView.MeterType.DOWNLOAD) {
			binding.iconArrow.setImageResource(R.drawable.ic_arrow_down)
			binding.iconArrow.setColorFilter(ContextCompat.getColor(context, R.color.speed_download_wave_light))
			binding.tvLabel.text = "Descarga"

			// ← El color base del borde y minichart será el naranja de la app
			baseColor = ContextCompat.getColor(context, R.color.speed_download_wave_light)

		} else {
			binding.iconArrow.setImageResource(R.drawable.ic_arrow_up)
			binding.iconArrow.setColorFilter(ContextCompat.getColor(context, R.color.speed_upload_wave_light))
			binding.tvLabel.text = "Carga"

			// ← El color base será azul
			baseColor = ContextCompat.getColor(context, R.color.speed_upload_wave_light)
		}

		// Fondo translúcido premium CON corner radius preservados
		binding.root.background = GradientDrawable().apply {
			cornerRadius = 32f  // Bordes redondeados más pronunciados
			setColor("#0DFFFFFF".toColorInt())
			setStroke(2, "#33FFFFFF".toColorInt())
		}
	}

	fun updateSpeed(bytes: Float) {

		// --- 1. Calcular unidad ---
		val speed: Float
		val unit: String

		when {
			bytes >= 1_000_000f -> {
				speed = bytes / 1_000_000f
				unit = "MB/s"
			}
			bytes >= 1_000f -> {
				speed = bytes / 1_000f
				unit = "KB/s"
			}
			else -> {
				speed = bytes
				unit = "B/s"
			}
		}

		binding.tvSpeedValue.text = String.format("%.1f", speed)
		binding.tvSpeedUnit.text = unit


		// --- 2. Intensidad dinámica (0 → 1) ---
		// Con 20,000 como divisor: 10 KB/s (10,240 bytes) = intensidad ~0.5 (media perceptible)
		val intensity = (bytes / 20_000f).coerceIn(0f, 1f)


		// --- 3. Color dinámico basado SOLO en baseColor ---
		val targetColor = ColorUtils.blendARGB(
			baseColor,          // naranja (download) o azul (upload)
			Color.WHITE,
			intensity * 0.45f   // qué tanto brilla
		)

		// --- 4. Animación del color (Lerp suave) ---
		val animator = ValueAnimator.ofObject(ArgbEvaluator(), lastAnimatedColor, targetColor)
		animator.duration = 300

		animator.addUpdateListener { anim ->

			val animatedColor = anim.animatedValue as Int

			// Crear un drawable con glow usando stroke grueso y transparente
			val glowLayer = GradientDrawable().apply {
				cornerRadius = 32f
				setColor("#00000000".toColorInt())  // Transparente
				// Stroke grueso para simular el glow alrededor de los bordes
				val glowAlpha = (intensity * 100).toInt().coerceIn(0, 60)
				val glowColor = ColorUtils.setAlphaComponent(animatedColor, glowAlpha)
				setStroke(8, glowColor)
			}

			// Crear el borde redondeado principal
			val mainBorder = GradientDrawable().apply {
				cornerRadius = 32f  // Mantener los mismos bordes redondeados
				setColor("#0DFFFFFF".toColorInt())
				setStroke(2, animatedColor)  // Actualizar stroke color dinámicamente
			}

			// Combinar en LayerDrawable: glow de fondo + borde principal
			val layerDrawable = LayerDrawable(arrayOf(glowLayer, mainBorder))
			layerDrawable.setLayerInset(1, 4, 4, 4, 4)  // El borde principal está inset respecto al glow

			// Asignar el drawable combinado al fondo del card
			binding.root.background = layerDrawable

			lastAnimatedColor = animatedColor
		}

		animator.start()


		// --- 5. Animación suave de la flecha ---
		animateArrow(intensity)
	}
	
	private fun animateArrow(intensity: Float) {
		val pulse = 1f + (0.20f * intensity)

		binding.iconArrow.animate()
			.scaleX(pulse)
			.scaleY(pulse)
			.setDuration(160)
			.withEndAction {
				binding.iconArrow.animate()
					.scaleX(1f)
					.scaleY(1f)
					.setDuration(160)
					.start()
			}
			.start()
	}

	fun setMaxSpeed(maxSpeed: String) {
		binding.tvMaxSpeed.text = maxSpeed
	}


}
