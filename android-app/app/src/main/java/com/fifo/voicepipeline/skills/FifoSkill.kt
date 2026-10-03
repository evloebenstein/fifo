package com.fifo.voicepipeline.skills

/**
 * Resultado de la ejecución de un Skill de Fifo.
 *
 * @property success Indica si la herramienta se ejecutó correctamente.
 * @property spokenFeedback Frase empática y limpia para que el TTS la lea al adulto mayor.
 * @property data Metadatos o información devuelta por la herramienta.
 */
data class SkillResult(
    val success: Boolean,
    val spokenFeedback: String,
    val data: Map<String, Any?> = emptyMap(),
    /** Datos adicionales de contexto para que Claude use en su respuesta (ej: extractos de conversaciones pasadas) */
    val additionalData: Map<String, Any?> = emptyMap()
)

/**
 * Interfaz base para cualquier herramienta o habilidad que Fifo puede invocar
 * autónomamente durante la conversación.
 */
interface FifoSkill {
    /** Nombre identificador de la herramienta para Function Calling (ej: "set_reminder") */
    val name: String

    /** Descripción clara de cuándo y cómo Claude debe utilizar esta herramienta */
    val description: String

    /** Esquema JSON en formato Anthropic Tools API para los argumentos requeridos */
    val parameterSchemaJson: String

    /**
     * Ejecuta la habilidad con los argumentos proporcionados por el LLM o el parser de voz.
     */
    suspend fun execute(args: Map<String, Any?>): SkillResult
}
