package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.location.FifoLocationHelper
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.ui.model.CurrentLocationInfo
import java.util.Locale

/**
 * Enrutador de Intenciones Rápidas Locales (Fast-Path Local Intent Router).
 *
 * Principio Arquitectónico Fundamental:
 * - Resuelve en microsegundos (<0.001 ms) ÚNICAMENTE comandos mecánicos y de hardware
 *   sin ambigüedad (colgar/atender llamadas, linterna, volumen, hora, batería, descanso).
 * - NUNCA intercepta consultas conversacionales, recuerdos, personas, anécdotas, recomendaciones,
 *   recetas médicas, vestimentas o búsquedas de lugares en el pasado:
 *   todas esas consultas retornan `null` para que Groq LPU realice razonamiento multi-paso
 *   con el pipeline GraphRAG y web_search.
 */
class VoiceIntentRouter(private val context: Context? = null) {

    companion object {
        private const val TAG = "VoiceIntentRouter"
    }

    /**
     * Intenta resolver una intención de voz en local si es un comando físico directo.
     * Retorna [SkillResult] si se resolvió localmente, o `null` si debe ser procesada por Groq LPU.
     */
    suspend fun routeFastIntent(
        transcript: String,
        executeSkill: suspend (name: String, args: Map<String, Any?>) -> SkillResult
    ): SkillResult? {
        val text = transcript.lowercase().trim()
        if (text.isBlank()) return null

        // ── FILTRO PREVIO: Si la consulta es una pregunta de memoria, personal o conversacional,
        // PASA DIRECTO A GROQ LPU (Retorna null inmediatamente) ──────────────────────────
        if (isConversationalOrMemoryQuery(text)) {
            runCatching { Log.d(TAG, "Consulta conversacional/memoria detectada -> delegando a Groq LPU: '$text'") }
            return null
        }

        // ── 1. GESTIÓN CRÍTICA DE LLAMADAS TELEFÓNICAS (Prioridad #1 Absoluta) ────────────
        val isCallAnswer = text in listOf("contesta", "contestar", "atiende", "atender", "acepta", "aceptar") ||
                text.contains("contesta la llamada") || text.contains("contestar la llamada") ||
                text.contains("atiende la llamada") || text.contains("atender la llamada") ||
                text.contains("acepta la llamada") || text.contains("aceptar la llamada") ||
                text.contains("sí contesta") || text.contains("si contesta") ||
                text.contains("toma la llamada") || text.contains("tomar la llamada") ||
                text.contains("responde la llamada") || text.contains("responder llamada") ||
                (text.contains("responde") && (text.contains("llamada") || text.contains("fifo")))

        if (isCallAnswer) {
            return executeSkill("manage_phone_call", mapOf("action" to "answer"))
        }

        val isCallHangup = text in listOf("cuelga", "colgar", "rechaza", "rechazar", "corta", "cortar") ||
                text.contains("corta la llamada") || text.contains("cortar la llamada") ||
                text.contains("cuelga la llamada") || text.contains("colgar la llamada") ||
                text.contains("detén la llamada") || text.contains("deten la llamada") ||
                text.contains("rechaza la llamada") || text.contains("rechazar la llamada") ||
                text.contains("no contestes") || text.contains("no contestar") ||
                text.contains("termina la llamada") || text.contains("terminar la llamada") ||
                text.contains("finaliza la llamada") || text.contains("cancela la llamada") ||
                (text.contains("corta") && (text.contains("llamada") || text.contains("fifo") || text.length <= 8))

        if (isCallHangup) {
            return executeSkill("manage_phone_call", mapOf("action" to "hangup"))
        }

        if (text.contains("quién llama") || text.contains("quien llama") ||
            text.contains("quién está llamando") || text.contains("quien esta llamando") ||
            text.contains("quién me llama") || text.contains("quien me llama")) {
            return executeSkill("manage_phone_call", mapOf("action" to "status"))
        }

        // ── 2. MODO ESCUCHA CONTINUA ─────────────────────────────────────────────────────
        if (text.contains("sigue escuchando") || text.contains("quédate escuchando") ||
            text.contains("quedate escuchando") || text.contains("modo continuo") ||
            text.contains("modo conversación") || text.contains("no te duermas") ||
            text.contains("sigue atento")) {
            FifoDataRepository.setContinuousListening(true)
            return SkillResult(
                success = true,
                spokenFeedback = "Modo continuo activado."
            )
        }

        // ── 3. COMANDOS DE DESCANSO / SILENCIO / DESPEDIDA ───────────────────────────────
        if (text.contains("deja de escuchar") || text.contains("ya no escuches") || text.contains("silencio") ||
            text.contains("descansa") || text.contains("duérmete") || text.contains("duermete") ||
            text.contains("apágate") || text.contains("apagate") || text.contains("cállate") || text.contains("callate") ||
            text.contains("no necesito nada") || text.contains("no quiero nada") || text.contains("eso es todo") ||
            text.contains("eso sería todo") || text.contains("listo fifo") || text.contains("chao fifo") ||
            text == "chao" || text == "adios" || text == "adiós" || text == "hasta luego") {
            FifoDataRepository.setContinuousListening(false)
            return SkillResult(
                success = true,
                spokenFeedback = "Fifo descansando.",
                data = mapOf("go_to_sleep" to true)
            )
        }

        // ── 4. LOCALIZADOR FÍSICO DEL ROBOT FIFO (Beep sonoro) ───────────────────────────
        if (text.contains("te perdí") || text.contains("te perdi") ||
            text.contains("dónde estás fifo") || text.contains("donde estas fifo") ||
            text.contains("dónde está fifo") || text.contains("donde esta fifo") ||
            (text.contains("suena") && text.contains("fifo"))) {
            return executeSkill("find_fifo_device", emptyMap())
        }

        // ── 5. CONTROL DE HARDWARE DEL TELÉFONO ───────────────────────────────────────────
        // Linterna
        if (text.contains("linterna") || (text.contains("luz") && (text.contains("prende") || text.contains("enciende") || text.contains("apaga")))) {
            val turnOn = !text.contains("apaga") && !text.contains("desactiva")
            return executeSkill("control_device_hardware", mapOf("feature" to "flashlight", "state" to if (turnOn) "on" else "off"))
        }

        // Volumen
        if (text.contains("volumen") || text.contains("más fuerte") || text.contains("más despacio") ||
            text.contains("más alto") || text.contains("más bajo")) {
            val state = when {
                text.contains("máximo") || text.contains("todo el volumen") -> "max"
                text.contains("mínimo") || text.contains("silencio") -> "min"
                text.contains("baja") || text.contains("despacio") || text.contains("menos") || text.contains("bajo") -> "down"
                else -> "up"
            }
            return executeSkill("control_device_hardware", mapOf("feature" to "volume", "state" to state))
        }

        // Batería
        if (text.contains("batería") || text.contains("bateria") || text.contains("cuánta carga") || text.contains("cuanta carga")) {
            return executeSkill("control_device_hardware", mapOf("feature" to "battery"))
        }

        // Hora y Fecha Directa ("¿Qué hora es?", "¿Qué fecha es?")
        val isTimeOrDate = (text.contains("hora") && (text.contains("qué") || text.contains("que") || text.contains("dime") || text.contains("sabes") || text.contains("tienes") || text == "la hora" || text == "¿la hora?")) ||
                (text.contains("día es") || text.contains("dia es") || text.contains("fecha es") || text.contains("qué día") || text.contains("que dia") || text.contains("qué fecha") || text.contains("que fecha"))
        if (isTimeOrDate && !text.contains("ayer") && !text.contains("año") && !text.contains("semana") && !text.contains("cuando")) {
            return executeSkill("control_device_hardware", mapOf("feature" to "time"))
        }

        // ── 6. CONFIRMACIÓN NATURAL DE DESTINO PENDIENTE ───────────────────────────────────
        @Suppress("DEPRECATION")
        val pending = FifoNavigationManager.instance.pendingDestination
        if (pending != null) {
            val cleanAffirmative = text.replace(Regex("[¿?.,!¡]"), "").trim()
            val isYes = cleanAffirmative in listOf(
                "si", "sí", "bueno", "dale", "vamos", "guiame", "guíame", "claro",
                "por favor", "porfa", "adelante", "ok", "okay", "obvio", "hazlo",
                "inicia", "comienza", "si por favor", "sí por favor", "si porfa",
                "sí porfa", "si guiame", "sí guíame", "dale vamos", "dale fifo",
                "bueno fifo", "si fifo", "sí fifo", "sipo", "sip", "claro que si", "claro que sí",
                "por favor fifo", "vamos fifo"
            ) || cleanAffirmative.startsWith("si ") || cleanAffirmative.startsWith("sí ") ||
                    cleanAffirmative.startsWith("dale ") || cleanAffirmative.startsWith("guiame") ||
                    cleanAffirmative.startsWith("guíame") || cleanAffirmative.startsWith("vamos")

            if (isYes) {
                @Suppress("DEPRECATION")
                val active = FifoNavigationManager.instance.confirmPendingDestination()
                if (active != null) {
                    val targetName = active.name

                    return SkillResult(
                        success = true,
                        spokenFeedback = "Ruta iniciada hacia $targetName.",
                        data = mapOf(
                            "destination" to active.name,
                            "started_navigation" to true,
                            "keep_listening" to true
                        )
                    )
                }
            }
        }

        // ── 7. NAVEGACIÓN PEATONAL ACTIVA (Solo si ya está en curso) ──────────────────────
        @Suppress("DEPRECATION")
        if (FifoNavigationManager.instance.isNavigating) {
            // Cancelar ruta
            if (text.contains("cancela la ruta") || text.contains("cancela el viaje") ||
                text.contains("deja de guiarme") || text.contains("ya no voy") ||
                text.contains("detén la guía") || text.contains("deten la guia")) {
                @Suppress("DEPRECATION")
                val destName = FifoNavigationManager.instance.activeRoute?.name ?: "el destino"
                @Suppress("DEPRECATION")
                FifoNavigationManager.instance.stopNavigation()
                return SkillResult(
                    success = true,
                    spokenFeedback = "Entendido, detuve las indicaciones hacia $destName.",
                    data = mapOf("cancelled_navigation" to true)
                )
            }

            // Preguntas directas de orientación peatonal
            val isNavQuery = text.contains("dónde voy") || text.contains("donde voy") ||
                    text.contains("por dónde") || text.contains("por donde") ||
                    text.contains("hacia dónde") || text.contains("hacia donde") ||
                    text.contains("cuánto falta") || text.contains("cuanto falta") ||
                    text.contains("dónde sigo") || text.contains("donde sigo") ||
                    text.contains("me desvié") || text.contains("me desvie") ||
                    text.contains("voy bien") || text == "ahora" || text == "¿y ahora?"

            if (isNavQuery) {
                val currentLoc = if (context != null) FifoLocationHelper.getCurrentLocation(context) else CurrentLocationInfo(address = "Santiago Centro", city = "Santiago")
                @Suppress("DEPRECATION")
                val report = FifoNavigationManager.instance.getGuidanceReport(currentLoc)
                if (report != null) {
                    val distText = if (report.distanceMeters < 1200) "${report.distanceMeters} metros" else "${String.format(Locale("es", "ES"), "%.1f", report.distanceMeters / 1000.0)} km"

                    val spoken = if (report.isArrived) {
                        "Llegaste a ${report.destinationName}."
                    } else {
                        "${report.relativeInstruction} hacia ${report.destinationName} ($distText)."
                    }

                    return SkillResult(
                        success = true,
                        spokenFeedback = spoken,
                        data = mapOf(
                            "destination" to report.destinationName,
                            "relative_turn" to report.relativeInstruction,
                            "distance_meters" to report.distanceMeters,
                            "blocks" to report.blocks,
                            "is_arrived" to report.isArrived,
                            "keep_listening" to true
                        )
                    )
                }
            }
        }

        // ── 8. CONCIENCIA ESPACIAL INMEDIATA ("¿Dónde estoy ahora?") ───────────────────────
        val isSpatialQuery = (text.contains("dónde estoy") || text.contains("donde estoy") ||
                text.contains("en qué calle") || text.contains("en que calle") ||
                text.contains("dónde me encuentro") || text.contains("donde me encuentro") ||
                text.contains("dónde estamos") || text.contains("donde estamos")) &&
                !text.contains("ayer") && !text.contains("semana") && !text.contains("año")
        if (isSpatialQuery) {
            val currentLoc = if (context != null) FifoLocationHelper.getCurrentLocation(context) else CurrentLocationInfo(address = "Santiago Centro", city = "Santiago")
            val spatialDesc = if (context != null) {
                @Suppress("DEPRECATION")
                FifoNavigationManager.instance.getSpatialAwarenessDescription(currentLoc)
            } else {
                "Te encuentras en ${currentLoc.address}, ${currentLoc.city}."
            }
            return SkillResult(
                success = true,
                spokenFeedback = spatialDesc,
                data = mapOf(
                    "address" to currentLoc.address,
                    "city" to currentLoc.city,
                    "spatial_awareness" to true,
                    "keep_listening" to true
                )
            )
        }

        // TODAS LAS DEMÁS PREGUNTAS (Memoria episódica, qué dijo una persona, recomendaciones,
        // recetas, vestimentas, dudas, noticias, etc.) pasan a Groq LPU:
        return null
    }

    /**
     * Verifica si una frase corresponde a una consulta conversacional, memoria episódica,
     * recomendación o razonamiento abierto que requiere pasar al LLM.
     */
    private fun isConversationalOrMemoryQuery(text: String): Boolean {
        val memoryTriggers = listOf(
            "te acuerdas", "te acordás", "recuerdas", "¿recuerdas", "¿te acuerdas",
            "qué dijo", "que dijo", "qué me dijo", "que me dijo", "dijo",
            "te mencioné", "te mencione", "te conté", "te conte", "hablamos",
            "receta", "doctora", "doctor", "médico", "medico", "pastilla",
            "llevaba puesto", "llevaba puesta", "ropa", "vestida", "vestido",
            "restaurante", "local", "año pasado", "semana pasada", "ayer",
            "cómo era que se llamaba", "como era que se llamaba", "cómo se llamaba",
            "sigue abierto", "sigue funcionando", "cerró", "cerro", "horario",
            "recomiéndame", "recomiendame", "qué opinas", "que opinas",
            "por qué", "por que", "sabes si", "sabes qué", "cuéntame", "cuentame"
        )
        return memoryTriggers.any { text.contains(it) }
    }
}
