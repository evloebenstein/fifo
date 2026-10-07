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
        registerSkill(ContextRecallSkill(context))
        registerSkill(FindFifoDeviceSkill(context))
        registerSkill(CurrentLocationSkill(context))
        registerSkill(NavigationDirectionsSkill(context))
        registerSkill(PhoneCallSkill(context))
        registerSkill(PhoneDeviceControlSkill(context))
        registerSkill(ContactsSkill(context))
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
     * Genera la lista de herramientas en formato JSON compatible con OpenAI y Groq API.
     */
    fun getOpenAiToolsJson(): String {
        val toolsList = skills.values.map { skill ->
            val schemaObj = try {
                JsonParser.parseString(skill.parameterSchemaJson)
            } catch (e: Exception) {
                JsonParser.parseString("{}")
            }
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to skill.name,
                    "description" to skill.description,
                    "parameters" to schemaObj
                )
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

        // 0. MODO ESCUCHA CONTINUA ("Fifo sigue escuchando")
        if (text.contains("sigue escuchando") || text.contains("quédate escuchando") || text.contains("quedate escuchando") || text.contains("modo continuo") || text.contains("modo conversación") || text.contains("no te duermas") || text.contains("sigue atento")) {
            com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(true)
            return SkillResult(
                success = true,
                spokenFeedback = "Entendido Lucía, me quedo escuchándole con atención. Puede hablarme cuando guste sin decir 'Fifo'. Cuando desee que descanse, solo dígame 'Fifo, descansa'."
            )
        }
        if (text.contains("deja de escuchar") || text.contains("ya no escuches") || text.contains("silencio") || text.contains("descansa fifo") || text.contains("puedes descansar") || text.contains("gracias fifo") || text.contains("listo fifo")) {
            com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
            return SkillResult(
                success = true,
                spokenFeedback = "Con gusto, me quedo en reposo. Llámeme diciendo 'Fifo' cuando me necesite."
            )
        }

        // 0.1 GESTIÓN DE LLAMADAS TELEFÓNICAS TIPO ALEXA (Contestar / Colgar / Saber quién llama)
        val isCallAnswerIntent = text.contains("contesta") || text.contains("contestar") || text.contains("contéstame") ||
                text.contains("atiende") || text.contains("atender") || text.contains("atiéndeme") ||
                text.contains("acepta la llamada") || text.contains("aceptar la llamada") || text.contains("acepta") ||
                text.contains("aceptar") || text.contains("sí contesta") || text.contains("si contesta") ||
                text.contains("responder la llamada") || text.contains("responde la llamada") ||
                text.contains("responde") || text.contains("responder") ||
                text.contains("toma la llamada") || text.contains("tomar la llamada")

        if (isCallAnswerIntent) {
            return executeSkill("manage_phone_call", mapOf("action" to "answer"))
        }

        val isCallHangupIntent = text.contains("cuelga") || text.contains("colgar") ||
                text.contains("rechaza") || text.contains("rechazar") || text.contains("recházale") ||
                text.contains("deten la llamada") || text.contains("detén la llamada") || text.contains("detener llamada") ||
                text.contains("no contestes") || text.contains("no contestar") ||
                text.contains("corta la llamada") || text.contains("cortar la llamada") || text.contains("corta llamada") ||
                text.contains("termina la llamada") || text.contains("terminar la llamada") ||
                text.contains("finaliza la llamada") || text.contains("finalizar la llamada") ||
                text.contains("finaliza") || text.contains("cancela la llamada") ||
                (text.contains("corta") && (text.contains("llamada") || text.contains("fifo") || text.length <= 8))

        if (isCallHangupIntent) {
            return executeSkill("manage_phone_call", mapOf("action" to "hangup"))
        }

        if (text.contains("quién llama") || text.contains("quien llama") || text.contains("quién está llamando") || text.contains("quien esta llamando") || text.contains("quién me llama") || text.contains("quien me llama")) {
            return executeSkill("manage_phone_call", mapOf("action" to "status"))
        }

        // 0.2 CONTROL DE HARDWARE DEL TELÉFONO (Linterna, Volumen, Batería, Hora)
        if (text.contains("linterna") || (text.contains("luz") && (text.contains("prende") || text.contains("enciende") || text.contains("apaga")))) {
            val turnOn = !text.contains("apaga") && !text.contains("desactiva")
            return executeSkill("control_device_hardware", mapOf("feature" to "flashlight", "state" to if (turnOn) "on" else "off"))
        }

        if (text.contains("volumen") || text.contains("más fuerte") || text.contains("más despacio") || text.contains("más alto") || text.contains("más bajo")) {
            val state = when {
                text.contains("máximo") || text.contains("todo el volumen") -> "max"
                text.contains("mínimo") || text.contains("silencio") -> "min"
                text.contains("baja") || text.contains("despacio") || text.contains("menos") || text.contains("bajo") -> "down"
                else -> "up"
            }
            return executeSkill("control_device_hardware", mapOf("feature" to "volume", "state" to state))
        }

        if (text.contains("batería") || text.contains("bateria") || text.contains("cuánta carga") || text.contains("cuanta carga")) {
            return executeSkill("control_device_hardware", mapOf("feature" to "battery"))
        }

        if (text.contains("qué hora") || text.contains("que hora") || text.contains("dime la hora") || text.contains("qué día es") || text.contains("que dia es")) {
            return executeSkill("control_device_hardware", mapOf("feature" to "time"))
        }

        // 0.3 LOCALIZADOR Y GUÍA HABLADA SIN MIRAR EL CELULAR
        if (text.contains("te perdí") || text.contains("te perdi") || text.contains("dónde estás") || text.contains("donde estas") || text.contains("dónde está fifo") || text.contains("donde esta fifo") || (text.contains("suena") && text.contains("fifo"))) {
            return executeSkill("find_fifo_device", emptyMap())
        }

        if (text.contains("dónde estamos") || text.contains("donde estamos") || text.contains("dónde estoy") || text.contains("donde estoy") || text.contains("en qué calle") || text.contains("en que calle") || text.contains("cuál es mi ubicación")) {
            return executeSkill("get_current_location", emptyMap())
        }

        val isNavIntent = text.contains("cómo llego") || text.contains("como llego") ||
                text.contains("cómo llegar") || text.contains("como llegar") ||
                text.contains("cómo voy") || text.contains("como voy") ||
                text.contains("cómo ir") || text.contains("como ir") ||
                text.contains("instrucciones para") || text.contains("tener instrucciones") ||
                text.contains("dame instrucciones") || text.contains("indicaciones para") ||
                text.contains("indicaciones hacia") || text.contains("guíame a") || text.contains("guiame a") ||
                text.contains("dirección hacia") || text.contains("direccion hacia") ||
                text.contains("hacia dónde voy") || text.contains("hacia donde voy") ||
                text.contains("ruta hacia") || text.contains("ruta para")

        if (isNavIntent) {
            val target = text.replace(
                Regex("(?i)\\b(fifo|por favor|cómo tener instrucciones para ir a|como tener instrucciones para ir a|cómo tener instrucciones para llegar a|como tener instrucciones para llegar a|cómo tener instrucciones|como tener instrucciones|dame instrucciones para ir a|dame instrucciones para llegar a|dame instrucciones para|dame instrucciones|instrucciones para ir a|instrucciones para ir|instrucciones para llegar a|instrucciones para llegar|instrucciones para|indicaciones para ir a|indicaciones para llegar a|indicaciones para|indicaciones hacia|cómo llego a|como llego a|cómo llego|como llego|cómo llegar a|como llegar a|cómo llegar|como llegar|cómo voy a|como voy a|cómo ir a|como ir a|cómo voy|como voy|cómo ir|como ir|guíame a|guiame a|dirección hacia|direccion hacia|hacia dónde voy para|hacia donde voy para|hacia dónde voy|hacia donde voy|ruta hacia|ruta para)\\b"),
                ""
            ).trim()
            val openScreen = text.contains("abre maps") || text.contains("abre waze") ||
                    text.contains("pantalla") || text.contains("en el celular") || text.contains("en mi celular") ||
                    text.contains("muestrame") || text.contains("muéstrame")
            return executeSkill("open_navigation_directions", mapOf("destination" to target, "open_screen_map" to openScreen))
        }

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

        // 2. BUSCAR EN MAPAS (Oxxo, Minimarkets, Farmacias, consultorios, parques)
        val wantsNearbyScreen = text.contains("abre maps") || text.contains("abre waze") ||
                text.contains("pantalla") || text.contains("en el celular") || text.contains("en mi celular") ||
                text.contains("muestrame") || text.contains("muéstrame") || text.contains("abre el mapa") || text.contains("en el mapa")

        if (text.contains("oxxo")) {
            return executeSkill(
                "search_nearby_places",
                mapOf("place_type" to "oxxo", "query_hint" to "OXXO", "open_screen_map" to wantsNearbyScreen)
            )
        }
        if (text.contains("farmacia") && (text.contains("dónde") || text.contains("donde") || text.contains("busca") || text.contains("cerca"))) {
            return executeSkill(
                "search_nearby_places",
                mapOf("place_type" to "farmacia", "query_hint" to "", "open_screen_map" to wantsNearbyScreen)
            )
        }
        if ((text.contains("consultorio") || text.contains("cesfam") || text.contains("hospital")) && (text.contains("busca") || text.contains("cerca") || text.contains("dónde"))) {
            return executeSkill(
                "search_nearby_places",
                mapOf("place_type" to "centro_salud", "query_hint" to "", "open_screen_map" to wantsNearbyScreen)
            )
        }
        if (text.contains("parque") && (text.contains("busca") || text.contains("cerca") || text.contains("dónde"))) {
            return executeSkill(
                "search_nearby_places",
                mapOf("place_type" to "parque", "query_hint" to "", "open_screen_map" to wantsNearbyScreen)
            )
        }

        // 2.1 CONSULTA DE CONTACTOS DEL TELÉFONO
        if (text.contains("contacto") && (text.contains("ver") || text.contains("tienes") || text.contains("puedes") || text.contains("lista") || text.contains("mis contactos"))) {
            return executeSkill("read_phone_contacts", mapOf("action" to "list"))
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
