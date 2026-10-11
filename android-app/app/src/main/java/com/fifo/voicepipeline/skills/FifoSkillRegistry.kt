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

    /** Último lugar o local consultado (para continuidad conversacional en navegación) */
    var lastQueriedPlace: String = ""

    /** Último tema de conversación activa en el dispositivo */
    var lastTopic: String = ""

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
        registerSkill(WebSearchSkill(context))
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
     * Genera la lista de herramientas en formato JSON compatible con OpenAI y Groq API,
     * sanitizando automáticamente los parámetros opcionales para admitir null (evitando error 400 en Groq).
     */
    fun getOpenAiToolsJson(): String {
        val toolsList = skills.values.map { skill ->
            val schemaObj = try {
                val parsed = JsonParser.parseString(skill.parameterSchemaJson)
                if (parsed.isJsonObject) {
                    val root = parsed.asJsonObject
                    if (root.has("properties") && root.get("properties").isJsonObject) {
                        val props = root.getAsJsonObject("properties")
                        val requiredSet = if (root.has("required") && root.get("required").isJsonArray) {
                            root.getAsJsonArray("required").mapNotNull { if (it.isJsonPrimitive) it.asString else null }.toSet()
                        } else emptySet()

                        for (propKey in props.keySet()) {
                            if (!requiredSet.contains(propKey)) {
                                val propElem = props.get(propKey)
                                if (propElem.isJsonObject) {
                                    val propObj = propElem.asJsonObject
                                    // 1. Ampliar el tipo para admitir null si es un primitivo
                                    if (propObj.has("type") && propObj.get("type").isJsonPrimitive) {
                                        val currentType = propObj.get("type").asString
                                        if (currentType != "null") {
                                            val arr = com.google.gson.JsonArray()
                                            arr.add(currentType)
                                            arr.add("null")
                                            propObj.add("type", arr)
                                        }
                                    }
                                    // 2. Si tiene enum, incluir null para que los LLM puedan pasar null
                                    if (propObj.has("enum") && propObj.get("enum").isJsonArray) {
                                        val enumArr = propObj.getAsJsonArray("enum")
                                        var hasNull = false
                                        for (elem in enumArr) {
                                            if (elem.isJsonNull) hasNull = true
                                        }
                                        if (!hasNull) {
                                            enumArr.add(com.google.gson.JsonNull.INSTANCE)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    root
                } else parsed
            } catch (e: Exception) {
                Log.w(TAG, "Error sanitizando schema para ${skill.name}: ${e.message}")
                try { JsonParser.parseString(skill.parameterSchemaJson) } catch (_: Exception) { JsonParser.parseString("{}") }
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
     * Genera la lista de herramientas en formato ultra-compacto para Groq,
     * reduciendo los tokens de herramientas en más del 65% para nunca rebasar el límite de 8,000 TPM.
     */
    fun getCompactOpenAiToolsJson(): String {
        val toolsList = skills.values.map { skill ->
            val schemaObj = try {
                val parsed = JsonParser.parseString(skill.parameterSchemaJson)
                if (parsed.isJsonObject) {
                    val root = parsed.asJsonObject
                    if (root.has("properties") && root.get("properties").isJsonObject) {
                        val props = root.getAsJsonObject("properties")
                        val compactProps = com.google.gson.JsonObject()
                        for (propKey in props.keySet()) {
                            val propElem = props.get(propKey)
                            if (propElem.isJsonObject) {
                                val propObj = propElem.asJsonObject
                                val compactProp = com.google.gson.JsonObject()
                                if (propObj.has("type")) {
                                    compactProp.add("type", propObj.get("type"))
                                }
                                if (propKey == "open_screen_map") {
                                    compactProp.addProperty("description", "default false, only true if user explicitly asked to see screen map")
                                }
                                compactProps.add(propKey, compactProp)
                            }
                        }
                        root.add("properties", compactProps)
                    }
                    root
                } else parsed
            } catch (e: Exception) {
                JsonParser.parseString("{}")
            }
            val shortDesc = skill.description.split(".").firstOrNull()?.trim()?.ifBlank { skill.description } ?: skill.description
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to skill.name,
                    "description" to shortDesc.take(85),
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
        if (name == "search_nearby_places") {
            val q = (args["query_hint"] as? String) ?: (args["place_type"] as? String) ?: ""
            if (q.isNotBlank()) lastQueriedPlace = q
        } else if (name == "open_navigation_directions") {
            val d = (args["destination"] as? String) ?: ""
            if (d.isNotBlank()) lastQueriedPlace = d
        }
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

        // 0. GESTIÓN DE LLAMADAS TELEFÓNICAS (Contestar / Colgar / Saber quién llama)
        val isCallAnswerIntent = text.contains("contesta") || text.contains("contestar") ||
                text.contains("atiende") || text.contains("atender") ||
                text.contains("acepta la llamada") || text.contains("aceptar la llamada") ||
                text.contains("sí contesta") || text.contains("si contesta") ||
                text.contains("toma la llamada") || text.contains("tomar la llamada") ||
                text.contains("responde la llamada") || text.contains("responder llamada") ||
                (text.contains("responde") && (text.contains("llamada") || text.contains("fifo")))

        if (isCallAnswerIntent) {
            return executeSkill("manage_phone_call", mapOf("action" to "answer"))
        }

        val isCallHangupIntent = text.contains("cuelga") || text.contains("colgar") ||
                text.contains("rechaza") || text.contains("rechazar") ||
                text.contains("detén la llamada") || text.contains("deten la llamada") ||
                text.contains("no contestes") || text.contains("no contestar") ||
                text.contains("corta la llamada") || text.contains("cortar la llamada") || text.contains("corta llamada") ||
                text.contains("termina la llamada") || text.contains("terminar la llamada") ||
                text.contains("finaliza la llamada") || text.contains("finalizar la llamada") ||
                text.contains("cancela la llamada") ||
                (text.contains("corta") && (text.contains("llamada") || text.contains("fifo") || text.length <= 8))

        if (isCallHangupIntent) {
            return executeSkill("manage_phone_call", mapOf("action" to "hangup"))
        }

        if (text.contains("quién llama") || text.contains("quien llama") ||
            text.contains("quién está llamando") || text.contains("quien esta llamando") ||
            text.contains("quién me llama") || text.contains("quien me llama")) {
            return executeSkill("manage_phone_call", mapOf("action" to "status"))
        }

        // 0.1 MODO ESCUCHA CONTINUA ("Fifo sigue escuchando")
        if (text.contains("sigue escuchando") || text.contains("quédate escuchando") || text.contains("quedate escuchando") || text.contains("modo continuo") || text.contains("modo conversación") || text.contains("no te duermas") || text.contains("sigue atento")) {
            com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(true)
            val firstName = com.fifo.voicepipeline.data.FifoDataRepository.userProfile.value.fullName.split(" ").firstOrNull { it.isNotBlank() } ?: ""
            val nameClause = if (firstName.isNotBlank()) " $firstName" else ""
            return SkillResult(
                success = true,
                spokenFeedback = "Entendido$nameClause, me quedo escuchándole con atención. Puede hablarme cuando guste sin decir 'Fifo'. Cuando desee que descanse, solo dígame 'Fifo, descansa'."
            )
        }

        // 0.0 Comandos explícitos de descanso / despedida / silencio
        if (text.contains("deja de escuchar") || text.contains("ya no escuches") || text.contains("silencio") ||
            text.contains("descansa") || text.contains("duérmete") || text.contains("duermete") ||
            text.contains("apágate") || text.contains("apagate") || text.contains("cállate") || text.contains("callate") ||
            text.contains("no necesito nada") || text.contains("no quiero nada") || text.contains("eso es todo") ||
            text.contains("eso sería todo") || text.contains("listo fifo") || text.contains("chao fifo") ||
            text == "chao" || text == "adios" || text == "adiós" || text == "hasta luego") {
            com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
            return SkillResult(
                success = true,
                spokenFeedback = "Entendido, me quedo descansando. Si me necesita, solo diga 'Fifo'.",
                data = mapOf("go_to_sleep" to true)
            )
        }

        // 0.1 LOCALIZADOR DEL DISPOSITIVO FÍSICO FIFO (Beep sonoro)
        if (text.contains("te perdí") || text.contains("te perdi") || text.contains("dónde estás fifo") || text.contains("donde estas fifo") || text.contains("dónde está fifo") || text.contains("donde esta fifo") || (text.contains("suena") && text.contains("fifo"))) {
            return executeSkill("find_fifo_device", emptyMap())
        }

        // 0.2 CONTROL DE HARDWARE DEL TELÉFONO (Linterna, Volumen, Batería)
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

        // 0.3 CONFIRMACIÓN NATURAL DE DESTINO PENDIENTE
        // Ej: Fifo preguntó "¿Desea que le guíe?" y el usuario dice "Sí", "Bueno", "Dale", "Guíame", "Por favor", etc.
        val pending = com.fifo.voicepipeline.location.FifoNavigationManager.pendingDestination
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
                val active = com.fifo.voicepipeline.location.FifoNavigationManager.confirmPendingDestination()
                if (active != null) {
                    val currentLoc = com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(context)
                    val report = com.fifo.voicepipeline.location.FifoNavigationManager.getGuidanceReport(currentLoc)
                    val street = report?.streetName ?: active.road.ifBlank { active.fullAddress }
                    val distMeters = report?.distanceMeters ?: 0
                    val distText = if (distMeters < 1200) "$distMeters metros" else "${String.format(java.util.Locale("es", "ES"), "%.1f", distMeters / 1000.0)} km"
                    val blocks = if ((report?.blocks ?: 1) <= 1) "1 cuadra" else "${report?.blocks ?: 1} cuadras"
                    val turn = report?.relativeInstruction ?: "camine hacia el frente"
                    val targetName = active.name

                    val phrasings = listOf(
                        "¡Perfecto, en marcha! Para orientarte: $turn. Avanza $distText (unas $blocks) por $street hacia $targetName. Te acompaño en el camino, avísame mientras caminas.",
                        "¡Excelente, vamos! ${turn.replaceFirstChar { it.uppercase() }}. Son aproximadamente $distText por $street hacia $targetName. Camina tranquilo, voy atento a tus pasos.",
                        "¡Listo, te voy guiando! ${turn.replaceFirstChar { it.uppercase() }} y continúa unas $blocks por $street hacia $targetName. Pregúntame lo que necesites mientras avanzas."
                    )
                    return SkillResult(
                        success = true,
                        spokenFeedback = phrasings.random(),
                        data = mapOf(
                            "destination" to active.name,
                            "started_navigation" to true,
                            "keep_listening" to true
                        )
                    )
                }
            }
        }

        // 0.4 ACOMPAÑAMIENTO ACTIVO EN TIEMPO REAL (Estilo Waze Peatonal con Giroscopio / Brújula)
        // Ejemplo: "¿y ahora dónde voy?", "¿por dónde sigo?", "¿hacia dónde?", "¿ahora qué hago?", "¿cuánto falta?"
        if (com.fifo.voicepipeline.location.FifoNavigationManager.isNavigating) {
            // Cancelación explícita de ruta
            if (text.contains("cancela la ruta") || text.contains("cancela el viaje") ||
                text.contains("deja de guiarme") || text.contains("ya no voy") ||
                text.contains("detén la guía") || text.contains("deten la guia")) {
                val destName = com.fifo.voicepipeline.location.FifoNavigationManager.activeRoute?.name ?: "el destino"
                com.fifo.voicepipeline.location.FifoNavigationManager.stopNavigation()
                return SkillResult(
                    success = true,
                    spokenFeedback = "Entendido, detuve las indicaciones hacia $destName. Avísame si deseas ir a otro lugar.",
                    data = mapOf("cancelled_navigation" to true)
                )
            }

            val isNavQuery = text.contains("dónde voy") || text.contains("donde voy") ||
                    text.contains("a dónde") || text.contains("a donde") ||
                    text.contains("y ahora") || text.contains("por dónde") ||
                    text.contains("por donde") || text.contains("hacia dónde") ||
                    text.contains("hacia donde") || text.contains("para dónde") ||
                    text.contains("para donde") || text.contains("cuánto falta") ||
                    text.contains("cuanto falta") || text.contains("qué hago") ||
                    text.contains("que hago") || text.contains("adónde voy") ||
                    text.contains("adonde voy") || text.contains("hacia dónde era") ||
                    text.contains("hacia donde era") || text.contains("hacia dónde camino") ||
                    text.contains("hacia donde camino") || text.contains("cómo voy") ||
                    text.contains("como voy") || text == "ahora" || text == "¿y ahora?" ||
                    text.contains("dónde sigo") || text.contains("donde sigo") ||
                    text.contains("me desvié") || text.contains("me desvie") ||
                    text.contains("voy bien") || text.contains("estoy perdido") ||
                    text.contains("hacia qué lado") || text.contains("hacia que lado")

            if (isNavQuery) {
                val currentLoc = com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(context)
                val report = com.fifo.voicepipeline.location.FifoNavigationManager.getGuidanceReport(currentLoc)
                if (report != null) {
                    val distText = if (report.distanceMeters < 1200) "${report.distanceMeters} metros" else "${String.format(java.util.Locale("es", "ES"), "%.1f", report.distanceMeters / 1000.0)} km"
                    val blocks = if (report.blocks == 1) "1 cuadra" else "${report.blocks} cuadras"

                    val spoken = if (report.isArrived) {
                        "¡Ya llegamos! ${report.destinationName} se encuentra justo a tu lado."
                    } else if (text.contains("me desvié") || text.contains("me desvie") || text.contains("voy bien")) {
                        if (report.isOffCourse) {
                            "Sí, noto que doblaste en otra dirección. Para retomar el ${report.destinationName}: ${report.relativeInstruction} y avanza hacia el ${report.cardinalDirection}. Te quedan $distText."
                        } else {
                            "Vas muy bien, en dirección directa hacia ${report.destinationName}. ${report.relativeInstruction.replaceFirstChar { it.uppercase() }}; te faltan solo $distText ($blocks)."
                        }
                    } else {
                        val turn = report.relativeInstruction
                        val street = report.streetName
                        val destName = report.destinationName
                        val responses = listOf(
                            "Para ir a $destName: ${turn}. Avanza unas $blocks ($distText) por $street. Voy contigo.",
                            "Seguimos en camino hacia $destName. ${turn.replaceFirstChar { it.uppercase() }}; te quedan unos $distText por $street.",
                            "Vamos bien: ${turn.replaceFirstChar { it.uppercase() }} y continúa por $street. Faltan aproximadamente $distText para llegar a $destName."
                        )
                        responses.random()
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

        // 0.5 CONCIENCIA ESPACIAL Y AMBIENTAL ("Siente el espacio a tu alrededor")
        // Ej: "¿Fifo, dónde estoy?", "¿en qué calle estoy?", "¿qué tengo cerca?", "¿dónde me encuentro?"
        val isSpatialQuery = text.contains("dónde estoy") || text.contains("donde estoy") ||
                text.contains("en qué calle") || text.contains("en que calle") ||
                text.contains("dónde me encuentro") || text.contains("donde me encuentro") ||
                text.contains("dónde estamos") || text.contains("donde estamos") ||
                text.contains("qué hay alrededor") || text.contains("que hay alrededor") ||
                text.contains("qué tengo cerca") || text.contains("que tengo cerca") ||
                text.contains("alrededor mío") || text.contains("alrededor mio") ||
                text.contains("sientes dónde estoy") || text.contains("sientes donde estoy")
        if (isSpatialQuery) {
            val currentLoc = com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(context)
            val spatialDesc = com.fifo.voicepipeline.location.FifoNavigationManager.getSpatialAwarenessDescription(context, currentLoc)
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

        // 0.6 BÚSQUEDA DIRECTA DE LOCALES Y COMERCIOS CERCANOS (OXXO, Farmacia, Supermercado, etc.)
        // Evita latencia de red, errores de rate-limit 429 de Groq y alucinaciones de nombres de comuna
        val isNearbySearch = (text.contains("cercano") || text.contains("cercana") ||
                text.contains("más cerca") || text.contains("mas cerca") ||
                text.contains("dónde queda") || text.contains("donde queda") ||
                text.contains("dónde hay") || text.contains("donde hay") ||
                text.contains("cuál es el") || text.contains("cual es el") ||
                text.contains("qué mercado") || text.contains("que mercado") ||
                text.contains("qué local") || text.contains("que local") ||
                text.contains("qué locales") || text.contains("que locales") ||
                text.contains("qué supermercado") || text.contains("que supermercado") ||
                text.contains("hay algún") || text.contains("hay algun") ||
                text.contains("un oxxo") || text.contains("el oxxo")) &&
                (text.contains("oxxo") || text.contains("farmacia") || text.contains("supermercado") ||
                 text.contains("super") || text.contains("mercado") || text.contains("minimarket") ||
                 text.contains("panaderia") || text.contains("panadería") || text.contains("lider") ||
                 text.contains("jumbo") || text.contains("unimarc") || text.contains("banco") ||
                 text.contains("almacen") || text.contains("almacén") || text.contains("tienda") ||
                 text.contains("local") || text.contains("locales"))

        if (isNearbySearch) {
            val placeTarget = when {
                text.contains("oxxo") -> "oxxo"
                text.contains("farmacia") -> "farmacia"
                text.contains("supermercado") || text.contains("super") -> "supermercado"
                text.contains("panaderia") || text.contains("panadería") -> "panaderia"
                text.contains("mercado") -> "mercado"
                text.contains("minimarket") -> "minimarket"
                text.contains("lider") -> "lider"
                text.contains("jumbo") -> "jumbo"
                text.contains("unimarc") -> "unimarc"
                text.contains("banco") -> "banco"
                else -> "comercio"
            }
            return executeSkill("open_navigation_directions", mapOf("destination" to placeTarget, "real_time" to false))
        }

        // TODAS LAS DEMÁS CONSULTAS (Ubicación, guía en tiempo real, navegación, memoria, locales,
        // búsquedas, preguntas, seguimientos y conversación) DEBEN IR AL LLM CON CONTEXTO COMPLETO:
        return null
    }
}
