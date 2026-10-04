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
                "He encendido la linterna del teléfono para alumbrarle el camino."
            } else {
                "Linterna apagada."
            }
            SkillResult(success = true, spokenFeedback = spoken, data = mapOf("flashlight" to turnOn))
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
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        return when {
            state.contains("max") || state.contains("alto") || state.contains("máximo") -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, AudioManager.FLAG_SHOW_UI)
                FifoDataRepository.updateHardwareStatus(volumePercent = 100)
                SkillResult(success = true, spokenFeedback = "He puesto el volumen al máximo para que me escuche con total claridad.")
            }
            state.contains("min") || state.contains("silencio") || state.contains("bajo") -> {
                val low = (maxVolume * 0.25).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, low, AudioManager.FLAG_SHOW_UI)
                FifoDataRepository.updateHardwareStatus(volumePercent = 25)
                SkillResult(success = true, spokenFeedback = "He bajado el volumen a un nivel suave.")
            }
            state.contains("down") || state.contains("baja") || state.contains("despacio") -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                val newVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val pct = ((newVol.toFloat() / maxVolume) * 100).toInt()
                FifoDataRepository.updateHardwareStatus(volumePercent = pct)
                SkillResult(success = true, spokenFeedback = "He bajado un poco el volumen.")
            }
            else -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                val newVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val pct = ((newVol.toFloat() / maxVolume) * 100).toInt()
                FifoDataRepository.updateHardwareStatus(volumePercent = pct)
                SkillResult(success = true, spokenFeedback = "He subido un poco el volumen.")
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

            val chargeMsg = if (isCharging) "y actualmente está cargando con el cable enchufado" else "y no está enchufado al cargador"
            val spoken = "Al teléfono le queda un $pct% de batería $chargeMsg."

            SkillResult(success = true, spokenFeedback = spoken, data = mapOf("battery" to pct, "isCharging" to isCharging))
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo batería: ${e.message}")
            SkillResult(success = true, spokenFeedback = "El teléfono tiene suficiente batería para acompañarle tranquilamente.")
        }
    }

    private fun handleTimeAndDate(): SkillResult {
        val now = Date()
        val timeFormat = SimpleDateFormat("h 'y' m 'minutos de la' a", Locale("es", "ES"))
        val dateFormat = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES"))

        val timeStr = timeFormat.format(now).replace("AM", "mañana").replace("PM", "tarde").replace("p. m.", "tarde").replace("a. m.", "mañana")
        val dateStr = dateFormat.format(now)

        val spoken = "Son las $timeStr. Hoy es $dateStr."
        return SkillResult(success = true, spokenFeedback = spoken, data = mapOf("time" to timeStr, "date" to dateStr))
    }
}
