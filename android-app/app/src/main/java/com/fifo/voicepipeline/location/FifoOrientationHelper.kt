package com.fifo.voicepipeline.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Gestor de orientación espacial y brújula digital para navegación peatonal y en vehículo tipo Waze.
 *
 * Utiliza los sensores físicos del teléfono (Sensor de Vector de Rotación, o combinación de
 * Acelerómetro + Magnetómetro / Giroscopio) para determinar en tiempo real hacia qué dirección cardinal
 * está apuntando el teléfono celular del usuario.
 *
 * Esto permite indicarle al usuario instrucciones precisas en relación con su cuerpo y vista:
 * - "Dé media vuelta (el destino está detrás de usted)"
 * - "Gire hacia su derecha"
 * - "Continúe recto en la dirección que está mirando"
 */
object FifoOrientationHelper : SensorEventListener {

    private const val TAG = "FifoOrientationHelper"

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magneticSensor: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)

    private var hasAccelerometer = false
    private var hasMagnetometer = false

    /** Azimut actual del teléfono en grados (0° a 360°: 0° = Norte, 90° = Oriente, 180° = Sur, 270° = Poniente) */
    @Volatile
    var currentAzimuthDegrees: Float = 0f
        private set

    /** Nombre de la dirección cardinal actual hacia donde apunta el teléfono */
    val currentCardinalHeading: String
        get() = azimuthToCardinal(currentAzimuthDegrees)

    fun init(context: Context) {
        if (sensorManager != null) return
        val appContext = context.applicationContext
        val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm

        rotationVectorSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor != null) {
            sm.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Sensor de orientación iniciado: TYPE_ROTATION_VECTOR")
        } else {
            accelerometerSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magneticSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            accelerometerSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magneticSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            Log.i(TAG, "Sensor de orientación iniciado: ACCEL + MAG (Fallback)")
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // orientationAngles[0] está en radianes [-PI, PI]
                val azimuthRad = orientationAngles[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                if (azimuthDeg < 0) {
                    azimuthDeg += 360f
                }
                currentAzimuthDegrees = azimuthDeg
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
                hasAccelerometer = true
                updateOrientationFromAccelMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
                hasMagnetometer = true
                updateOrientationFromAccelMag()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun updateOrientationFromAccelMag() {
        if (!hasAccelerometer || !hasMagnetometer) return
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthRad = orientationAngles[0]
            var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
            if (azimuthDeg < 0) {
                azimuthDeg += 360f
            }
            currentAzimuthDegrees = azimuthDeg
        }
    }

    /**
     * Convierte el azimut en grados a nombre cardinal en español.
     */
    fun azimuthToCardinal(azimuth: Float): String {
        val norm = (azimuth + 360f) % 360f
        return when (norm) {
            in 337.5f..360f, in 0f..22.5f -> "el norte"
            in 22.5f..67.5f -> "el nororiente"
            in 67.5f..112.5f -> "el oriente"
            in 112.5f..157.5f -> "el suroriente"
            in 157.5f..202.5f -> "el sur"
            in 202.5f..247.5f -> "el suroccidente"
            in 247.5f..292.5f -> "el poniente"
            else -> "el norponiente"
        }
    }

    /**
     * Calcula la instrucción de giro relativo ("gire a la derecha", "dé media vuelta", etc.)
     * comparando hacia dónde apunta físicamente el teléfono del usuario con el rumbo hacia el destino.
     */
    fun getRelativeTurnInstruction(userAzimuth: Float, targetBearing: Float): RelativeTurnInfo {
        val diff = (((targetBearing - userAzimuth + 360f) % 360f)).toInt()
        val (instruction, isFacingTarget) = when (diff) {
            in 0..25, in 335..360 -> "continúe recto en la dirección que apunta su teléfono" to true
            in 26..70 -> "gire suavemente hacia su derecha" to false
            in 71..115 -> "gire a su derecha" to false
            in 116..160 -> "gire bastante hacia su derecha" to false
            in 161..200 -> "dé media vuelta (el camino queda justo a sus espaldas)" to false
            in 201..245 -> "gire bastante hacia su izquierda" to false
            in 246..290 -> "gire a su izquierda" to false
            else -> "gire suavemente hacia su izquierda" to false
        }

        return RelativeTurnInfo(
            differenceDegrees = diff,
            instruction = instruction,
            isFacingTarget = isFacingTarget
        )
    }
}

data class RelativeTurnInfo(
    val differenceDegrees: Int,
    val instruction: String,
    val isFacingTarget: Boolean
)
