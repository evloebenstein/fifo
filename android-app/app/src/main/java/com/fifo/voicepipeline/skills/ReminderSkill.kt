package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository

/**
 * Skill para gestionar recordatorios de medicamentos, compromisos familiares y hábitos de salud.
 *
 * Guarda el recordatorio en [FifoDataRepository] (reflejándose en la app)
 * y emite una confirmación hablada para el usuario.
 */
class ReminderSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "ReminderSkill"
    }

    override val name: String = "set_reminder"

    override val description: String =
        "Programa un recordatorio o alarma para el usuario (por ejemplo medicamentos como pastillas de la presión, citas, llamadas a familiares o hábitos diarios)."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "title": {
                "type": "string",
                "description": "Texto del recordatorio (ej: 'Tomar pastilla de la presión', 'Llamar a mi hija Carmen')"
            },
            "time_str": {
                "type": "string",
                "description": "Hora o momento del recordatorio en formato 'HH:mm' o texto amigable (ej: '20:00', 'en 15 minutos')"
            },
            "category": {
                "type": "string",
                "enum": ["medication", "family", "hobby", "health"],
                "description": "Categoría temática del recordatorio"
            }
        },
        "required": ["title", "time_str"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val title = args["title"]?.toString()?.trim() ?: "Recordatorio"
        val timeStr = args["time_str"]?.toString()?.trim() ?: "en un momento"
        val category = args["category"]?.toString()?.trim() ?: "medication"

        Log.i(TAG, "Guardando recordatorio: '$title' para las $timeStr (categoría: $category)")

        // 1. Guardar en el repositorio central de datos (se refleja en la app de inmediato)
        val item = FifoDataRepository.addReminder(
            title = title,
            timeStr = timeStr,
            category = category
        )

        val spokenText = when (category) {
            "medication" -> "Listo, ya le programé el recordatorio para $title a las $timeStr. Yo le avisaré con cariño para que no se le pase."
            "family" -> "Anotado. A las $timeStr le recordaré $title."
            else -> "Perfecto, le dejé programado su recordatorio de $title para las $timeStr."
        }

        return SkillResult(
            success = true,
            spokenFeedback = spokenText,
            data = mapOf("reminder_id" to item.id, "title" to title, "time" to timeStr)
        )
    }
}
