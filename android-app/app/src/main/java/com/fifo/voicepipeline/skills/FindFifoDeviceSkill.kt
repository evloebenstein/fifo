package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.location.FifoLocationHelper

/**
 * Skill que permite a Fifo responder cuando el usuario dice que perdió el dispositivo robot
 * o pregunta "¿dónde estás?" / "te perdí".
 *
 * ARQUITECTURA DE LOCALIZACIÓN:
 * Dado que el ESP32 no tiene módulo GPS integrado propio:
 * 1. Si está conectado por Bluetooth: Fifo sabe que está cerca (en la casa/habitación)
 *    y emite una melodía sonora alegre por su parlante (beeper) + ilumina su pantalla con "¡AQUÍ ESTOY!".
 * 2. Si está desconectado: Consulta el registro de la última conexión guardada por el GPS del celular
 *    (coordenadas, dirección, habitación aproximada y hora de última conexión) y abre el mapa en el teléfono.
 */
class FindFifoDeviceSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "FindFifoDeviceSkill"
    }

    override val name: String = "find_fifo_device"

    override val description: String =
        "Ayuda a encontrar el robot físico Fifo cuando el usuario dice que lo perdió, pregunta 'dónde estás', 'te perdí' o pide que emita un sonido para encontrarlo en la casa o en la calle."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["locate", "beep", "last_known_place"],
                "description": "Acción a realizar: 'locate' para buscar y reportar ubicación, 'beep' para hacerlo sonar con una melodía, 'last_known_place' para ver dónde se vio por última vez"
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = args["action"]?.toString()?.trim() ?: "locate"
        val devLoc = FifoDataRepository.deviceLocation.value

        Log.i(TAG, "Ejecutando FindFifoDeviceSkill: action=$action, isConnected=${devLoc.isConnected}")

        return if (devLoc.isConnected) {
            // El robot está conectado por Bluetooth cerca
            FifoDataRepository.triggerDeviceBeep(true)

            val firstName = FifoDataRepository.userProfile.value.fullName.split(" ").firstOrNull { it.isNotBlank() } ?: ""
            val nameClause = if (firstName.isNotBlank()) " $firstName" else ""
            SkillResult(
                success = true,
                spokenFeedback = "¡Aquí estoy$nameClause! Estoy conectado por Bluetooth y muy cerca de usted. Estoy haciendo sonar una melodía por mi parlante para que me escuche. ¡Siga el sonido!",
                data = mapOf(
                    "status" to "connected",
                    "rssi" to devLoc.signalStrengthRssi,
                    "room" to devLoc.lastKnownRoom
                )
            )
        } else {
            // El robot está desconectado: recurrir a la última ubicación GPS guardada si existe
            val hasCoordinates = devLoc.lastKnownLatitude != 0.0 && devLoc.lastKnownLongitude != 0.0
            if (hasCoordinates) {
                try {
                    FifoLocationHelper.openMapPin(
                        context = context,
                        latitude = devLoc.lastKnownLatitude,
                        longitude = devLoc.lastKnownLongitude,
                        label = "Última ubicación de Fifo"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir mapa automático: ${e.message}")
                }
            }

            val feedback = buildString {
                append("En este momento no tengo conexión Bluetooth directa con el robot. ")
                if (hasCoordinates) {
                    append("Recuerdo que estuvimos juntos por última vez ${devLoc.lastConnectedTime} en ${devLoc.lastKnownAddress}, cerca de ${devLoc.lastKnownRoom}. ")
                    append("Le acabo de abrir el mapa en su teléfono con el punto exacto para que lo encuentre fácilmente.")
                } else {
                    append("Aún no tenemos registrada una última ubicación GPS en este dispositivo. Le sugiero revisar en su mesa de noche o cerca de su lugar habitual de descanso.")
                }
            }

            SkillResult(
                success = true,
                spokenFeedback = feedback,
                data = mapOf(
                    "status" to "disconnected",
                    "last_seen_time" to devLoc.lastConnectedTime,
                    "last_address" to devLoc.lastKnownAddress,
                    "latitude" to devLoc.lastKnownLatitude,
                    "longitude" to devLoc.lastKnownLongitude
                )
            )
        }
    }
}
