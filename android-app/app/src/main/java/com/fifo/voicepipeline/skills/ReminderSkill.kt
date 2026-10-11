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
                "enum": ["medication", "family", "hobby", "health", "sport"],
                "description": "Categoría temática del recordatorio ('medication', 'family', 'sport', 'health', 'hobby')"
            }
        },
        "required": ["title", "time_str"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val title = args["title"]?.toString()?.trim() ?: "Recordatorio"
        val timeStr = args["time_str"]?.toString()?.trim() ?: "en un momento"
        val category = args["category"]?.toString()?.trim() ?: "hobby"

        // 1. Verificar si el recordatorio menciona un contacto del grafo relacional
        var enrichedTitle = title
        val foundContact = FifoDataRepository.findContactByRoleOrName(title)
        if (foundContact != null && !title.contains(foundContact.contactName, ignoreCase = true)) {
            enrichedTitle = "$title (${foundContact.contactName})"
        }

        // 2. Verificar si coincide con una rutina diaria conocida
        val matchingRoutine = FifoDataRepository.dailyRoutines.value.firstOrNull { routine ->
            val normRoutine = routine.routineName.lowercase()
            val normTitle = title.lowercase()
            normTitle.contains(normRoutine) || normRoutine.contains(normTitle) ||
            (normTitle.contains("presion") && normRoutine.contains("presion")) ||
            (normTitle.contains("orquidea") && normRoutine.contains("orquidea")) ||
            (normTitle.contains("caminata") && normRoutine.contains("caminata")) ||
            (normTitle.contains("paseo") && normRoutine.contains("caminata"))
        }

        // 3. Guardar en el repositorio central de datos (se refleja en la app de inmediato)
        val item = FifoDataRepository.addReminder(
            title = enrichedTitle,
            timeStr = timeStr,
            category = category
        )

        // Si coincide con una rutina, marcar reminderAssociated = true
        if (matchingRoutine != null && !matchingRoutine.reminderAssociated) {
            FifoDataRepository.addOrUpdateDailyRoutine(matchingRoutine.copy(reminderAssociated = true))
        }

        val routineNote = if (matchingRoutine != null) " como parte de su rutina habitual" else ""

        val spokenText = when (category) {
            "medication" -> "Listo, ya le programé el recordatorio para $enrichedTitle a las $timeStr$routineNote. Yo le avisaré con cariño para que no se le pase."
            "family" -> "Anotado. A las $timeStr le recordaré $enrichedTitle$routineNote."
            "sport", "health" -> "¡Excelente! Le he dejado agendado su recordatorio para $enrichedTitle a las $timeStr$routineNote."
            else -> "Perfecto, le dejé programado su recordatorio de $enrichedTitle para las $timeStr$routineNote."
        }

        return SkillResult(
            success = true,
            spokenFeedback = spokenText,
            data = mapOf(
                "reminder_id" to item.id,
                "title" to enrichedTitle,
                "time" to timeStr,
                "routine_linked" to (matchingRoutine?.routineName ?: "none")
            )
        )
    }
}
