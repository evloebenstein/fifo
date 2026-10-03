package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Registro y despachador central de Skills de Fifo.
 *
 * Se encarga de:
 * 1. Registrar todas las herramientas nativas del teléfono y mutaciones de BD.
 * 2. Generar el catálogo de herramientas en formato Anthropic Tools API para Claude.
 * 3. Ejecutar las herramientas solicitadas por el LLM.
 * 4. Proveer un reconocedor de intenciones por voz inmediato como fallback local.
 */
class FifoSkillRegistry(private val context: Context) {

    companion object {
        private const val TAG = "FifoSkillRegistry"
    }

    private val skills = mutableMapOf<String, FifoSkill>()
    private val gson = Gson()

    init {
        registerSkill(ReminderSkill(context))
        registerSkill(CalendarSkill(context))
        registerSkill(MapsSkill(context))
        registerSkill(PhoneCommunicationSkill(context))
        registerSkill(ProfileDatabaseSkill(context))
        Log.i(TAG, "Inicializado FifoSkillRegistry con ${skills.size} herramientas.")
    }

    fun registerSkill(skill: FifoSkill) {
        skills[skill.name] = skill
    }

    fun getSkill(name: String): FifoSkill? = skills[name]

    /**
     * Genera la lista de herramientas en formato JSON compatible con Anthropic Messages API.
     */
    fun getAnthropicToolsJson(): String {
        val toolsList = skills.values.map { skill ->
            val schemaObj = try {
                JsonParser.parseString(skill.parameterSchemaJson)
            } catch (e: Exception) {
                JsonParser.parseString("{}")
            }
            mapOf(
                "name" to skill.name,
                "description" to skill.description,
                "input_schema" to schemaObj
            )
        }
        return gson.toJson(toolsList)
    }

    /**
     * Ejecuta una herramienta por nombre con los argumentos recibidos.
     */
    suspend fun executeSkill(name: String, args: Map<String, Any?>): SkillResult {
        val skill = skills[name]
        return if (skill != null) {
            try {
                skill.execute(args)
            } catch (e: Exception) {
                Log.e(TAG, "Error ejecutando skill '$name': ${e.message}", e)
                SkillResult(
                    success = false,
                    spokenFeedback = "Disculpe, tuve un inconveniente al realizar esa acción en su teléfono."
                )
            }
        } else {
            Log.w(TAG, "Skill no encontrada: '$name'")
            SkillResult(
                success = false,
                spokenFeedback = "No encontré cómo realizar esa acción específica."
            )
        }
    }

    /**
     * Reconocedor de intenciones local por voz para respuesta instantánea.
     * Permite que frases comunes ("recuérdame la pastilla", "busca farmacias", "mi cumpleaños es...")
     * se ejecuten de inmediato sin esperar latencia de red.
     */
    suspend fun tryExecuteVoiceIntent(transcript: String): SkillResult? {
        val text = transcript.lowercase().trim()

        // 1. RECORDATORIOS / ALARMAS DE MEDICINA
        if (text.contains("recuérdame") || text.contains("recuerdame") || text.contains("recordar") || text.contains("pon una alarma") || text.contains("alarma para")) {
            val title = if (text.contains("pastilla") || text.contains("presión") || text.contains("medicina") || text.contains("remedio")) {
                "Tomar medicamento recetado"
            } else if (text.contains("agua")) {
                "Tomar un vaso de agua fresca"
            } else if (text.contains("caminar") || text.contains("paseo")) {
                "Salir a caminar a la plaza"
            } else {
                transcript.replace(Regex("(?i)\\b(recuérdame|recuerdame|por favor|fifo|recordar|alarma para)\\b"), "").trim()
                    .ifBlank { "Compromiso diario" }
            }

            // Extraer hora si dice "a las X"
            val hourRegex = Regex("(?i)a las\\s+(\\d{1,2}(?::\\d{2})?|ocho|nueve|diez|once|doce|una|dos|tres|cuatro|cinco|seis|siete)")
            val match = hourRegex.find(text)
            val timeStr = match?.groupValues?.get(1)?.let { rawHour ->
                when (rawHour.lowercase()) {
                    "ocho" -> "20:00"
                    "nueve" -> "21:00"
                    "diez" -> "22:00"
                    "once" -> "11:00"
                    "doce" -> "12:00"
                    "una" -> "13:00"
                    "dos" -> "14:00"
                    "tres" -> "15:00"
                    "cuatro" -> "16:00"
                    "cinco" -> "17:00"
                    "seis" -> "18:00"
                    "siete" -> "19:00"
                    else -> if (rawHour.contains(":")) rawHour else "$rawHour:00"
                }
            } ?: "en el momento indicado"

            return executeSkill(
                "set_reminder",
                mapOf("title" to title, "time_str" to timeStr, "category" to "medication")
            )
        }

        // 2. BUSCAR EN MAPAS (Farmacias, consultorios, parques)
        if (text.contains("farmacia") && (text.contains("dónde") || text.contains("donde") || text.contains("busca") || text.contains("cerca"))) {
            return executeSkill(
                "search_nearby_places",
                mapOf("place_type" to "farmacia", "query_hint" to "")
            )
        }
        if ((text.contains("consultorio") || text.contains("cesfam") || text.contains("hospital")) && (text.contains("busca") || text.contains("cerca") || text.contains("dónde"))) {
            return executeSkill(
                "search_nearby_places",
                mapOf("place_type" to "centro_salud", "query_hint" to "")
            )
        }
        if (text.contains("parque") && (text.contains("busca") || text.contains("cerca") || text.contains("dónde"))) {
            return executeSkill(
                "search_nearby_places",
                mapOf("place_type" to "parque", "query_hint" to "")
            )
        }

        // 3. CALENDARIO DE CITAS
        if (text.contains("calendario") || text.contains("agenda") || (text.contains("cita") && text.contains("médic"))) {
            val title = if (text.contains("médic") || text.contains("doctor")) "Control médico con el especialista" else "Cita personal"
            return executeSkill(
                "add_calendar_event",
                mapOf("title" to title, "date_str" to "pronto", "time_str" to "10:00")
            )
        }

        // 4. LLAMAR A CONTACTO / FAMILIAR
        if (text.contains("llama a") || text.contains("llamar a") || text.contains("marca a") || text.contains("marcar a")) {
            val contact = if (text.contains("carmen") || text.contains("hija")) "hija Carmen"
            else if (text.contains("ambulancia") || text.contains("samu")) "SAMU 131"
            else if (text.contains("bombero")) "Bomberos 132"
            else "familiar de apoyo"

            return executeSkill("call_contact", mapOf("contact_name_or_role" to contact))
        }

        // 5. CUMPLEAÑOS Y FECHA DE NACIMIENTO
        if (text.contains("cumpleaños") || text.contains("cumplo el") || text.contains("nací el") || text.contains("naci el")) {
            val yearMatch = Regex("\\b(19\\d{2})\\b").find(text)
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

            val rawDate = transcript.replace(Regex("(?i)\\b(mi cumpleaños es el|cumplo el|nací el|naci el|fifo)\\b"), "").trim()
            val cleanDate = if (rawDate.length in 5..30) rawDate else "14 de Mayo, 1958"

            return executeSkill(
                "update_profile_and_tastes",
                mapOf(
                    "action" to "update_demographics",
                    "birth_date" to cleanDate,
                    "birth_year" to (year ?: 1958)
                )
            )
        }

        // 6. GUSTOS E INTERESES
        if (text.contains("me encanta") || text.contains("me gusta mucho") || text.contains("anota que me gusta") || text.contains("agrega a mis gustos")) {
            val taste = transcript
                .replace(Regex("(?i)\\b(me encanta|me gusta mucho|anota que me gusta|agrega a mis gustos|fifo|por favor)\\b"), "")
                .trim()
                .trimStart(',', '.', ':', ' ')
                .replaceFirstChar { it.uppercase() }

            if (taste.length in 3..40) {
                return executeSkill(
                    "update_profile_and_tastes",
                    mapOf("action" to "add_taste", "taste_name" to taste)
                )
            }
        }

        return null
    }
}
