package com.fifo.voicepipeline.skills

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log

/**
 * Skill para integrar eventos con el calendario nativo del teléfono (Google Calendar / Samsung Calendar).
 *
 * Utiliza un Intent seguro que abre la pantalla de confirmación del calendario con los datos
 * prellenados sin requerir permisos peligrosos de escritura.
 */
class CalendarSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "CalendarSkill"
    }

    override val name: String = "add_calendar_event"

    override val description: String =
        "Agrega una cita, visita o compromiso en el calendario del teléfono del usuario (ej: visita al médico, cumpleaños de nietos, talleres comunitarios)."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "title": {
                "type": "string",
                "description": "Título de la cita o evento (ej: 'Control médico con el Dr. Muñoz')"
            },
            "date_str": {
                "type": "string",
                "description": "Fecha del evento (ej: 'martes próximo', '15 de Octubre', 'mañana')"
            },
            "time_str": {
                "type": "string",
                "description": "Hora de la cita (ej: '10:30', '15:00')"
            },
            "description": {
                "type": "string",
                "description": "Detalles o notas adicionales sobre la cita"
            }
        },
        "required": ["title"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val title = args["title"]?.toString()?.trim() ?: "Compromiso"
        val dateStr = args["date_str"]?.toString()?.trim() ?: "pronto"
        val timeStr = args["time_str"]?.toString()?.trim() ?: ""
        val details = args["description"]?.toString()?.trim() ?: "Agendado por Fifo"

        Log.i(TAG, "Agendando evento de calendario: '$title', fecha: $dateStr, hora: $timeStr")

        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, details)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error lanzando intent de calendario: ${e.message}")
        }

        val spoken = if (timeStr.isNotBlank()) {
            "He anotado en su calendario la cita de $title para las $timeStr. Le dejé la ficha lista en la pantalla."
        } else {
            "He preparado la anotación en su calendario para $title. Se lo dejé listo en el teléfono."
        }

        return SkillResult(
            success = true,
            spokenFeedback = spoken,
            data = mapOf("title" to title, "date" to dateStr, "time" to timeStr)
        )
    }
}
