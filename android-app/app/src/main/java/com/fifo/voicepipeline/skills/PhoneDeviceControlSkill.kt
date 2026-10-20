package com.fifo.voicepipeline.skills

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Skill que dota a Fifo de capacidades tipo 'Alexa' controlando el hardware del teléfono:
 * - Linterna: "Fifo prende la linterna", "apaga la linterna"
 * - Volumen: "Fifo sube el volumen", "baja el volumen", "volumen al máximo"
 * - Batería: "Fifo cuánta batería le queda al celular"
 * - Hora y fecha: "Fifo qué hora es", "qué día es hoy"
 */
class PhoneDeviceControlSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "PhoneDeviceControlSkill"
    }

    override val name: String = "control_device_hardware"

    override val description: String =
        "Controla funciones del celular tipo asistente Alexa: encender o apagar la linterna ('flashlight'), ajustar el volumen del teléfono ('volume'), consultar el nivel de batería ('battery'), o consultar la hora y fecha actual ('time')."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "feature": {
                "type": "string",
                "enum": ["flashlight", "volume", "battery", "time"],
                "description": "Función a controlar: 'flashlight' (linterna), 'volume' (volumen), 'battery' (batería), 'time' (hora y fecha)"
            },
            "state": {
                "type": "string",
                "description": "Para linterna: 'on' o 'off'. Para volumen: 'up', 'down', 'max', 'min'."
            }
        },
        "required": ["feature"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val feature = args["feature"]?.toString()?.lowercase()?.trim() ?: "time"
        val state = args["state"]?.toString()?.lowercase()?.trim() ?: ""

        Log.i(TAG, "Ejecutando control_device_hardware: feature=$feature, state=$state")

        return when (feature) {
            "flashlight" -> handleFlashlight(state)
            "volume" -> handleVolume(state)
            "battery" -> handleBattery()
            "time" -> handleTimeAndDate()
            else -> SkillResult(success = false, spokenFeedback = "Función no reconocida en el teléfono.")
        }
    }

    private fun handleFlashlight(state: String): SkillResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            return SkillResult(success = false, spokenFeedback = "La linterna no está disponible en este dispositivo.")
        }

        val turnOn = state == "on" || state == "encender" || state == "prender" || state == "activar"

        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: "0"

            cameraManager.setTorchMode(cameraId, turnOn)
            FifoDataRepository.updateHardwareStatus(isFlashlightOn = turnOn)

            val spoken = if (turnOn) {
                "Listo, ya le encendí la linterna del teléfono para que pueda alumbrar y ver con calma el camino."
            } else {
                "He apagado la linterna. Si necesita más luz en cualquier momento, solo avíseme."
            }
            SkillResult(
                success = true,
                spokenFeedback = spoken,
                data = mapOf("feature" to "flashlight", "state" to if (turnOn) "on" else "off", "flashlight" to turnOn)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error controlando linterna: ${e.message}")
            SkillResult(
                success = false,
                spokenFeedback = "No pude cambiar el estado de la linterna en este momento."
            )
        }
    }

    private fun handleVolume(state: String): SkillResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return SkillResult(success = false, spokenFeedback = "No se pudo acceder al control de audio.")

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        return when {
            state.contains("max") || state.contains("alto") || state.contains("máximo") -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, AudioManager.FLAG_SHOW_UI)
                FifoDataRepository.updateHardwareStatus(volumePercent = 100)
                SkillResult(
                    success = true,
                    spokenFeedback = "He puesto el volumen al máximo para que me escuche con total claridad.",
                    data = mapOf("feature" to "volume", "state" to "max", "volumePercent" to 100)
                )
            }
            state.contains("min") || state.contains("silencio") || state.contains("bajo") -> {
                val low = (maxVolume * 0.25).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, low, AudioManager.FLAG_SHOW_UI)
                FifoDataRepository.updateHardwareStatus(volumePercent = 25)
                SkillResult(
                    success = true,
                    spokenFeedback = "He bajado el volumen a un nivel suave y tranquilo.",
                    data = mapOf("feature" to "volume", "state" to "min", "volumePercent" to 25)
                )
            }
            state.contains("down") || state.contains("baja") || state.contains("despacio") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                val newVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val pct = ((newVol.toFloat() / maxVolume) * 100).toInt()
                FifoDataRepository.updateHardwareStatus(volumePercent = pct)
                SkillResult(
                    success = true,
                    spokenFeedback = "He bajado el volumen para que se sienta más cómodo.",
                    data = mapOf("feature" to "volume", "state" to "down", "volumePercent" to pct)
                )
            }
            else -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                val newVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val pct = ((newVol.toFloat() / maxVolume) * 100).toInt()
                FifoDataRepository.updateHardwareStatus(volumePercent = pct)
                SkillResult(
                    success = true,
                    spokenFeedback = "He subido un poco el volumen para que me escuche con mayor claridad.",
                    data = mapOf("feature" to "volume", "state" to "up", "volumePercent" to pct)
                )
            }
        }
    }

    private fun handleBattery(): SkillResult {
        return try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, batteryFilter)

            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val pct = if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else 85
            FifoDataRepository.updateHardwareStatus(batteryPercent = pct, isCharging = isCharging)

            val spoken = when {
                isCharging -> "Tiene un $pct% de carga y actualmente está enchufado cargando con tranquilidad."
                pct < 20 -> "Le queda un $pct% de batería. Le recomiendo ponerlo a cargar pronto para no quedarse desconectado."
                else -> "Tiene un $pct% de batería restante, suficiente para acompañarle durante el día."
            }

            SkillResult(
                success = true,
                spokenFeedback = spoken,
                data = mapOf("feature" to "battery", "battery_level" to pct, "is_charging" to isCharging)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo batería: ${e.message}")
            SkillResult(
                success = true,
                spokenFeedback = "El teléfono tiene suficiente batería para acompañarle tranquilamente.",
                data = mapOf("feature" to "battery", "battery_level" to 85)
            )
        }
    }

    private fun handleTimeAndDate(): SkillResult {
        val calendar = Calendar.getInstance()
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val hour12 = calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val minute = calendar.get(Calendar.MINUTE)

        val period = when (hour24) {
            in 0..5 -> "de la madrugada"
            in 6..11 -> "de la mañana"
            in 12..19 -> "de la tarde"
            else -> "de la noche"
        }

        val minuteClause = when (minute) {
            0 -> "en punto"
            15 -> "y cuarto"
            30 -> "y media"
            else -> "y $minute minutos"
        }

        val dateFormat = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES"))
        val dateStr = dateFormat.format(calendar.time)

        val spoken = "Son las $hour12 $minuteClause $period. Hoy es $dateStr."
        return SkillResult(
            success = true,
            spokenFeedback = spoken,
            data = mapOf("hour" to hour24, "minute" to minute, "period" to period, "date" to dateStr)
        )
    }
}
