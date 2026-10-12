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
        "Gestiona los recordatorios y alarmas del usuario en la app: crear ('set'), marcar como completado ('complete'), eliminar ('delete') o listar pendientes ('list')."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["set", "complete", "delete", "list"],
                "description": "Acción a realizar sobre los recordatorios: 'set' para programar, 'complete' para marcar como tomado/hecho, 'delete' para borrar/cancelar, 'list' para consultar pendientes"
            },
            "reminder_id": {
                "type": "string",
                "description": "Identificador único del recordatorio si se conoce (opcional)"
            },
            "title": {
                "type": "string",
                "description": "Texto o asunto del recordatorio (ej: 'Tomar pastilla de la presión', 'Llamar a mi hija Carmen')"
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
        }
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = args["action"]?.toString()?.trim()?.lowercase() ?: "set"
        val reminderId = args["reminder_id"]?.toString()?.trim()
        val title = args["title"]?.toString()?.trim() ?: ""

        when (action) {
            "complete" -> {
                val allReminders = FifoDataRepository.reminders.value
                val target = if (!reminderId.isNullOrBlank()) {
                    allReminders.firstOrNull { it.id == reminderId }
                } else if (title.isNotBlank()) {
                    allReminders.firstOrNull { it.title.contains(title, ignoreCase = true) && !it.isCompleted }
                        ?: allReminders.firstOrNull { it.title.contains(title, ignoreCase = true) }
                } else {
                    allReminders.firstOrNull { !it.isCompleted }
                }

                if (target == null) {
                    return SkillResult(
                        success = false,
                        spokenFeedback = "No encontré ese recordatorio pendiente para marcarlo como completado."
                    )
                }

                FifoDataRepository.completeReminder(target.id)
                return SkillResult(
                    success = true,
                    spokenFeedback = "¡Listo! He marcado '${target.title}' como completado en tus recordatorios.",
                    data = mapOf("reminder_id" to target.id, "title" to target.title, "completed" to true)
                )
            }

            "delete" -> {
                val allReminders = FifoDataRepository.reminders.value
                val target = if (!reminderId.isNullOrBlank()) {
                    allReminders.firstOrNull { it.id == reminderId }
                } else if (title.isNotBlank()) {
                    allReminders.firstOrNull { it.title.contains(title, ignoreCase = true) }
                } else null

                if (target == null) {
                    return SkillResult(
                        success = false,
                        spokenFeedback = "No encontré ese recordatorio para eliminarlo."
                    )
                }

                FifoDataRepository.removeReminder(target.id)
                return SkillResult(
                    success = true,
                    spokenFeedback = "Listo, he eliminado el recordatorio '${target.title}' de tu lista.",
                    data = mapOf("reminder_id" to target.id, "deleted" to true)
                )
            }

            "list" -> {
                val active = FifoDataRepository.reminders.value.filter { !it.isCompleted }
                if (active.isEmpty()) {
                    return SkillResult(
                        success = true,
                        spokenFeedback = "Por ahora no tienes ningún recordatorio pendiente. ¡Todo al día!",
                        data = mapOf("count" to 0)
                    )
                }

                val listText = active.joinToString(", ") { "${it.title} a las ${it.timeStr}" }
                return SkillResult(
                    success = true,
                    spokenFeedback = "Tienes ${active.size} recordatorios pendientes: $listText.",
                    data = mapOf("count" to active.size, "reminders" to active.map { mapOf("id" to it.id, "title" to it.title, "time" to it.timeStr) })
                )
            }

            else -> { // "set"
                val taskTitle = title.ifBlank { "Recordatorio" }
                val timeStr = args["time_str"]?.toString()?.trim() ?: "en un momento"
                val category = args["category"]?.toString()?.trim() ?: "hobby"

                // 1. Verificar si el recordatorio menciona un contacto del grafo relacional
                var enrichedTitle = taskTitle
                val foundContact = FifoDataRepository.findContactByRoleOrName(taskTitle)
                if (foundContact != null && !taskTitle.contains(foundContact.contactName, ignoreCase = true)) {
                    enrichedTitle = "$taskTitle (${foundContact.contactName})"
                }

                // 2. Verificar si coincide con una rutina diaria conocida
                val matchingRoutine = FifoDataRepository.dailyRoutines.value.firstOrNull { routine ->
                    val normRoutine = routine.routineName.lowercase()
                    val normTitle = taskTitle.lowercase()
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
    }
}
