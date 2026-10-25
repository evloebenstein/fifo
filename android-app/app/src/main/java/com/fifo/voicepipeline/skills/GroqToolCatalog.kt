package com.fifo.voicepipeline.skills

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Catálogo de Herramientas para Groq LPU (GroqToolCatalog).
 *
 * Responsabilidad:
 * - Genera esquemas de herramientas 100% compatibles con la API OpenAI / Groq Function Calling.
 * - Mantiene el catálogo en formato ultra-compacto (<1,100 tokens) para respetar el límite de 7,000 ITPM de Groq.
 * - Enriquece semánticamente la herramienta 'recall_past_context' para soportar el pipeline GraphRAG:
 *   recuperación de anécdotas, vestimentas, recetas médicas, qué dijo una persona y detección de lagunas.
 * - Mantiene un catálogo puro de esquemas OpenAI/Groq, sin dependencias de proveedores retirados.
 */
class GroqToolCatalog {

    companion object {
        private const val TAG = "GroqToolCatalog"
    }

    private val gson = Gson()

    /**
     * Genera la lista de herramientas en formato ultra-compacto para Groq LPU,
     * reduciendo los tokens del catálogo a ~1,070 tokens (63% de reducción)
     * para nunca rebasar el límite de 7,000 ITPM en Groq.
     */
    fun getCompactToolsJson(): String {
        val compactTools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "recall_past_context",
                    "description" to "Consulta la memoria profunda y el grafo de conocimiento del usuario: anécdotas, conversaciones pasadas, qué dijo una persona (familia, doctora), vestimentas, recetas médicas, nombres de locales olvidados o temas. Permite identificar lagunas o confirmar que nunca se habló de un tema.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf(
                                "type" to "string",
                                "description" to "Término, persona, lugar, receta o pista temporal a buscar en la memoria profunda"
                            ),
                            "context_hint" to mapOf(
                                "type" to "string",
                                "description" to "Pista opcional: 'persona', 'receta_medica', 'lugar', 'vestimenta', 'fecha'"
                            )
                        ),
                        "required" to listOf("query")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "web_search",
                    "description" to "Busca en internet en tiempo real: noticias recientes, clima actual, o verifica si un local/restaurante sigue abierto o cambió de dirección.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf("type" to "string", "description" to "Consulta a buscar en la web"),
                            "search_type" to mapOf(
                                "type" to "string",
                                "enum" to listOf("general", "news", "weather", "encyclopedia"),
                                "description" to "Tipo de búsqueda"
                            )
                        ),
                        "required" to listOf("query")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "search_nearby_places",
                    "description" to "Busca comercios o locales cercanos por GPS (oxxo, farmacia, minimarket)",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "place_type" to mapOf("type" to "string"),
                            "query_hint" to mapOf("type" to "string")
                        )
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "open_navigation_directions",
                    "description" to "Guía navegación a un destino o confirma destino sugerido (destination=confirm_pending)",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "destination" to mapOf("type" to "string"),
                            "open_screen_map" to mapOf("type" to "boolean", "description" to "default false, solo true si pide ver mapa")
                        ),
                        "required" to listOf("destination")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "manage_navigation_session",
                    "description" to "Estado de una ruta activa: anota encargos/paradas en el camino (add_stop), pausa o pide más tiempo (pause), reanuda cuando el usuario está listo (resume), corrige la ruta si el usuario dice que está mal (correct).",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf(
                                "type" to "string",
                                "enum" to listOf("add_stop", "complete_stops", "pause", "resume", "correct", "status")
                            ),
                            "label" to mapOf("type" to "string", "description" to "Encargo o parada (ej: comprar pan)"),
                            "reason" to mapOf("type" to "string", "description" to "Motivo de la pausa"),
                            "minutes" to mapOf("type" to "integer", "description" to "Minutos de pausa; omitir si es indefinida"),
                            "correction" to mapOf("type" to "string", "description" to "Corrección de ruta del usuario")
                        ),
                        "required" to listOf("action")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "get_current_location",
                    "description" to "Obtiene ubicación GPS actual y dirección física",
                    "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "manage_phone_call",
                    "description" to "Llamadas del teléfono: make_call, answer, end_call",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "phone_number" to mapOf("type" to "string"),
                            "contact_name" to mapOf("type" to "string")
                        ),
                        "required" to listOf("action")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "read_phone_contacts",
                    "description" to "Contactos del celular: search, list, dictate",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "search_query" to mapOf("type" to "string")
                        ),
                        "required" to listOf("action")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "control_device_hardware",
                    "description" to "Control: linterna, volumen, batería, hora",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "level" to mapOf("type" to "integer")
                        ),
                        "required" to listOf("action")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "manage_reminders",
                    "description" to "Recordatorios: add, list, delete",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "title" to mapOf("type" to "string"),
                            "time_str" to mapOf("type" to "string")
                        ),
                        "required" to listOf("action")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "manage_calendar_events",
                    "description" to "Eventos de calendario: add, list, delete",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "title" to mapOf("type" to "string"),
                            "date" to mapOf("type" to "string")
                        ),
                        "required" to listOf("action")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "send_sms_message",
                    "description" to "Envía mensaje SMS a un contacto",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "phone_number" to mapOf("type" to "string"),
                            "message" to mapOf("type" to "string")
                        ),
                        "required" to listOf("message")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "find_fifo_device",
                    "description" to "Hace sonar el robot Fifo para encontrarlo",
                    "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "update_profile_and_tastes",
                    "description" to "Guarda gustos, recuerdos o datos del usuario en la BD",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "action" to mapOf("type" to "string"),
                            "taste_name" to mapOf("type" to "string"),
                            "detail" to mapOf("type" to "string")
                        ),
                        "required" to listOf("action")
                    )
                )
            )
        )
        return gson.toJson(compactTools)
    }

    /**
     * Genera la lista de herramientas completas sanitizadas a partir de los skills registrados.
     */
    fun getDynamicToolsJson(skills: Collection<FifoSkill>): String {
        val toolsList = skills.map { skill ->
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
                                    if (propObj.has("type") && propObj.get("type").isJsonPrimitive) {
                                        val currentType = propObj.get("type").asString
                                        if (currentType != "null") {
                                            val arr = com.google.gson.JsonArray()
                                            arr.add(currentType)
                                            arr.add("null")
                                            propObj.add("type", arr)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    root
                } else parsed
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "Error sanitizando schema para ${skill.name}: ${e.message}") }
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
}
