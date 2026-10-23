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
 *    se inyectan automáticamente en cada llamada al LLM como contexto previo.
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
        "Busca en la memoria profunda y el grafo de conocimiento relacional de Fifo (GraphRAG): lugares visitados en el pasado, qué restaurante era, qué dijo una persona o doctora sobre recetas médicas, qué ropa llevaba puesta alguien, anécdotas, gustos, o temas olvidados. Úsalo SIEMPRE que el usuario diga 'te acuerdas...', 'qué me dijo...', 'a dónde fui...', o pregunte por algo hablado anteriormente. Detecta si faltan detalles o si nunca hablaron de eso."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Término, lugar, comida, persona, receta o pista temporal a buscar en la memoria (ej: 'restaurante con María año pasado', 'receta doctora', 'vestimenta nieta', 'dónde fui ayer')"
            },
            "context_hint": {
                "type": "string",
                "description": "Pista adicional sobre qué tipo de información buscas (ej: 'nombre de local', 'detalle de receta', 'fecha de evento', 'persona', 'ropa')"
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

        Log.i(TAG, "Buscando contexto profundo GraphRAG: query='$query', hint='$contextHint'")

        // Buscar en el backend GraphRAG con fallback local multinivel
        val result = FifoDataRepository.searchDeepContext(query)

        return if (result.relevantExcerpts.isNotEmpty()) {
            Log.i(TAG, "Contexto encontrado: ${result.conversationsSearched} charlas revisadas, ${result.relevantExcerpts.size} coincidencias")

            // El feedback incluye el contexto sintetizado para que Groq formule
            // una respuesta cálida, precisa y sin alucinaciones al usuario
            SkillResult(
                success = true,
                spokenFeedback = result.synthesizedContext,
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
