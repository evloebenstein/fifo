package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository

/**
 * Skill que permite a Fifo buscar contexto profundo de conversaciones pasadas
 * cuando la ventana de contexto compacto (on-device) no tiene suficiente detalle.
 *
 * ARQUITECTURA DE DOBLE CAPA:
 * 1. Capa local (rápida): Los últimos N fragmentos compactos (temas clave, resumen ≤150 palabras)
 *    se inyectan automáticamente en cada llamada a Claude como contexto previo.
 * 2. Capa profunda (este skill): Cuando Fifo detecta que el usuario menciona un tema
 *    de nicho o un dato pasado que no está en los fragmentos recientes, invoca este skill
 *    para buscar en la base de datos del servidor la conversación completa y extraer
 *    solo los extractos relevantes.
 *
 * Ejemplos de activación:
 * - "¿Te acuerdas cuando te conté del recetario de mi abuela?"
 * - "¿Cómo era que se llamaba esa planta que te describí?"
 * - "¿Qué me dijiste la vez que hablamos de los paseos?"
 */
class ContextRecallSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "ContextRecallSkill"
    }

    override val name: String = "recall_past_context"

    override val description: String =
        "Busca en la memoria profunda y registros pasados de Fifo: lugares visitados ayer o la semana pasada, nombres de locales olvidados, anécdotas, compras, gustos, personas y temas de charlas anteriores. Úsalo SIEMPRE que el usuario diga 'a dónde fui ayer', 'no me acuerdo cómo se llamaba ese local', o pregunte por algo que hicieron o hablaron antes."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Término, lugar, comida, persona o pista temporal a buscar en la memoria (ej: 'local ayer', 'empanadas', 'recetario abuela', 'orquídeas', 'dónde fui ayer')"
            },
            "context_hint": {
                "type": "string",
                "description": "Pista adicional sobre qué tipo de información buscas (ej: 'nombre de local', 'detalle de receta', 'fecha de evento', 'persona')"
            }
        },
        "required": ["query"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val query = args["query"]?.toString()?.trim() ?: ""
        val contextHint = args["context_hint"]?.toString()?.trim() ?: ""

        if (query.isBlank()) {
            return SkillResult(
                success = false,
                spokenFeedback = "No alcancé a entender qué necesita que recuerde. ¿Puede repetirlo?"
            )
        }

        Log.i(TAG, "Buscando contexto profundo: query='$query', hint='$contextHint'")

        // Buscar en los fragmentos locales (en producción esto llamará al servidor)
        val result = FifoDataRepository.searchDeepContext(query)

        return if (result.relevantExcerpts.isNotEmpty()) {
            Log.i(TAG, "Contexto encontrado: ${result.conversationsSearched} charlas revisadas, ${result.relevantExcerpts.size} coincidencias")

            // El feedback incluye el contexto sintetizado para que Claude lo use
            // en su respuesta al usuario
            SkillResult(
                success = true,
                spokenFeedback = result.synthesizedContext,
                // Datos adicionales para que Claude pueda usar el contexto en su respuesta
                additionalData = mapOf(
                    "found_entities" to result.foundEntities.joinToString(", "),
                    "excerpts_count" to result.relevantExcerpts.size.toString(),
                    "conversations_searched" to result.conversationsSearched.toString()
                )
            )
        } else {
            Log.i(TAG, "No se encontró contexto para: '$query'")
            SkillResult(
                success = true,
                spokenFeedback = "Revisé mis recuerdos de nuestras charlas pasadas y no encontré algo específico sobre '$query'. ¿Puede darme más detalles para que lo busque mejor?"
            )
        }
    }
}
