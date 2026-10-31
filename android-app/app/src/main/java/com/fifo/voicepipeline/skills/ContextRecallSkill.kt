package com.fifo.voicepipeline.skills

import android.util.Log
import com.fifo.voicepipeline.data.model.DeepContextResult

/**
 * Skill que permite a Fifo buscar contexto profundo de conversaciones pasadas
 * cuando la ventana de contexto compacto (on-device) no tiene suficiente detalle.
 *
 * ARQUITECTURA DE DOBLE CAPA:
 * 1. Capa local (rápida): los últimos fragmentos compactos se inyectan en cada
 *    llamada al LLM como contexto previo.
 * 2. Capa profunda (este skill): el LLM la invoca cuando detecta una laguna o
 *    necesita un detalle. `ConversationContext.searchDeep()` consulta el
 *    backend y cae al índice local si no hay red.
 *
 * El resultado es **payload**, no una frase: el contexto recuperado viaja en
 * `data` y el LLM redacta la respuesta. Si no hay evidencia, se devuelve
 * `context_status = no_evidence` para que lo diga con honestidad; nunca se
 * inventa ni se usa una frase enlatada (ADR-0049).
 */
class ContextRecallSkill(
    private val deepSearch: suspend (String) -> DeepContextResult
) : FifoSkill {

    companion object {
        private const val TAG = "ContextRecallSkill"
    }

    override val name: String = "recall_past_context"

    override val description: String =
        "Busca en la memoria profunda y el grafo de conocimiento relacional de Fifo (GraphRAG): " +
            "lugares visitados, qué restaurante era, qué dijo una persona o doctora, anécdotas, " +
            "gustos o temas olvidados. Úsalo cuando el usuario pregunte por algo hablado antes o " +
            "cuando falte un detalle. La política completa de cuándo buscar está en el contexto del sistema."

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
                spokenFeedback = "",
                data = mapOf("context_status" to "missing_query")
            )
        }

        Log.i(TAG, "Buscando contexto profundo GraphRAG: query='$query', hint='$contextHint'")

        val result = deepSearch(query)

        return if (result.relevantExcerpts.isNotEmpty()) {
            Log.i(
                TAG,
                "Contexto encontrado: ${result.conversationsSearched} charlas revisadas, " +
                    "${result.relevantExcerpts.size} coincidencias"
            )
            SkillResult(
                success = true,
                spokenFeedback = "",
                data = mapOf(
                    "context_status" to "found",
                    "query" to query,
                    "synthesized_context" to result.synthesizedContext,
                    "excerpts" to result.relevantExcerpts.joinToString("\n"),
                    "found_entities" to result.foundEntities.joinToString(", "),
                    "conversations_searched" to result.conversationsSearched.toString()
                )
            )
        } else {
            Log.i(TAG, "No se encontró contexto para: '$query'")
            SkillResult(
                success = true,
                spokenFeedback = "",
                data = mapOf(
                    "context_status" to "no_evidence",
                    "query" to query
                )
            )
        }
    }
}
