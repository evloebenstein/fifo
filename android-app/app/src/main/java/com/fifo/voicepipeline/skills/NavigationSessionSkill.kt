package com.fifo.voicepipeline.skills

import android.util.Log
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.LocationAdapter

/**
 * Herramienta que mantiene el estado conversacional de una ruta activa.
 *
 * El usuario puede intercalar cosas mientras Fifo lo acompaña: comprar algo,
 * pedir unos minutos, conversar con alguien, corregir una indicación o decir que
 * ya está listo. Esta skill las registra en [FifoNavigationManager.sessionMemory]
 * para que el LLM las tenga en el contexto y pueda reorientar o retomar el
 * resumen sin repetir una locución fija.
 *
 * No redacta respuestas: devuelve datos (`planned_stops`, `paused`,
 * `relative_instruction`, etc.) y un `spokenFeedback` de emergencia.
 */
class NavigationSessionSkill(
    private val locationAdapter: LocationAdapter,
    private val navigationManager: FifoNavigationManager
) : FifoSkill {

    companion object {
        private const val TAG = "NavigationSessionSkill"
    }

    override val name: String = "manage_navigation_session"

    override val description: String =
        "Gestiona el estado conversacional de una ruta activa: anota encargos en el camino " +
            "(add_stop), marca pausas o más tiempo (pause), reanuda cuando el usuario está listo " +
            "(resume), registra una corrección de la ruta (correct), marca encargos realizados " +
            "(complete_stops) o consulta el estado (status)."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["add_stop", "complete_stops", "pause", "resume", "correct", "status"],
                "description": "Acción sobre la sesión de navegación"
            },
            "label": {
                "type": "string",
                "description": "Encargo o parada a recordar (ej: 'comprar pan', 'pasar a la farmacia')"
            },
            "reason": {
                "type": "string",
                "description": "Motivo de la pausa (ej: 'conversando con un vecino', 'descansando')"
            },
            "minutes": {
                "type": "integer",
                "description": "Minutos de pausa. Omitir para una pausa sin plazo."
            },
            "correction": {
                "type": "string",
                "description": "Lo que el usuario corrigió de la ruta o de la indicación"
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = (args["action"] as? String)?.trim()?.lowercase().orEmpty()
        val memory = navigationManager.sessionMemory

        return when (action) {
            "add_stop" -> {
                val label = (args["label"] as? String)?.trim().orEmpty()
                val stop = memory.addStop(label)
                    ?: return failure("Necesito saber qué desea hacer en el camino.")
                Log.i(TAG, "Encargo anotado en la ruta: ${stop.label}")
                SkillResult(
                    success = true,
                    spokenFeedback = "Anotado: ${stop.label}.",
                    data = snapshot() + mapOf(
                        "added_stop" to stop.label,
                        "planned_stops" to memory.pendingStops().map { it.label }
                    )
                )
            }

            "complete_stops" -> {
                val done = memory.completeStops()
                SkillResult(
                    success = true,
                    spokenFeedback = if (done.isEmpty()) "No tenía encargos anotados."
                    else "Listo, di por hechos ${done.size} encargos del camino.",
                    data = snapshot() + mapOf(
                        "completed_stops" to done.map { it.label },
                        "planned_stops" to memory.pendingStops().map { it.label }
                    )
                )
            }

            "pause" -> {
                val reason = (args["reason"] as? String)?.trim().orEmpty()
                val minutes = readMinutes(args["minutes"])
                val pause = memory.pause(reason, minutes)
                Log.i(TAG, "Pausa de navegación: '${pause.reason}' (${minutes ?: "sin plazo"} min)")
                val hint = if (minutes != null) {
                    "De acuerdo, lo espero unos $minutes minutos."
                } else {
                    "De acuerdo, lo espero. Avíseme cuando quiera seguir."
                }
                SkillResult(
                    success = true,
                    spokenFeedback = hint,
                    data = snapshot() + mapOf(
                        "paused" to true,
                        "pause_reason" to pause.reason,
                        "pause_minutes" to minutes,
                        "planned_stops" to memory.pendingStops().map { it.label }
                    )
                )
            }

            "resume" -> {
                val paused = memory.resume()
                SkillResult(
                    success = true,
                    spokenFeedback = if (paused != null) "Seguimos." else "Ya estábamos en camino.",
                    data = snapshot() + mapOf(
                        "paused" to false,
                        "resumed" to (paused != null),
                        "planned_stops" to memory.pendingStops().map { it.label }
                    )
                )
            }

            "correct" -> {
                val correction = (args["correction"] as? String)?.trim().orEmpty()
                if (correction.isBlank()) {
                    return failure("Necesito saber cuál es la corrección de la ruta.")
                }
                memory.recordCorrection(correction)
                Log.i(TAG, "Corrección de ruta registrada: $correction")
                SkillResult(
                    success = true,
                    spokenFeedback = "Gracias por corregirme; ajusto la indicación.",
                    data = snapshot() + mapOf(
                        "corrected_route" to correction,
                        "planned_stops" to memory.pendingStops().map { it.label }
                    )
                )
            }

            "status", "" -> SkillResult(
                success = true,
                spokenFeedback = "",
                data = snapshot() + mapOf(
                    "planned_stops" to memory.pendingStops().map { it.label },
                    "paused" to memory.isPaused(),
                    "pause_reason" to memory.pause?.reason.orEmpty(),
                    "pause_minutes" to memory.remainingPauseMinutes(),
                    "last_correction" to memory.lastCorrection.orEmpty()
                )
            )

            else -> failure("No reconozco esa acción de navegación.")
        }
    }

    private fun failure(message: String) = SkillResult(
        success = false,
        spokenFeedback = message,
        data = mapOf("route_active" to navigationManager.isNavigating)
    )

    /**
     * Estado actual de la ruta para que el LLM pueda responder con datos reales.
     */
    private suspend fun snapshot(): Map<String, Any?> {
        val dest = navigationManager.activeRoute
            ?: return mapOf("route_active" to false)

        val report = runCatching {
            navigationManager.getGuidanceReport(locationAdapter.getFreshLocation())
        }.getOrNull()

        return mapOf(
            "route_active" to true,
            "destination" to dest.name,
            "destination_address" to dest.fullAddress,
            "distance_meters" to report?.distanceMeters,
            "blocks" to report?.blocks,
            "walking_minutes" to report?.walkingMinutes,
            "relative_instruction" to report?.relativeInstruction,
            "cardinal_direction" to report?.cardinalDirection
        )
    }

    private fun readMinutes(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt().takeIf { it > 0 }
        is String -> raw.trim().toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }
}
