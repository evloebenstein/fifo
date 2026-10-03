package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository

/**
 * Skill que ejecuta mutaciones directamente sobre la base de datos de Fifo
 * ([FifoDataRepository]), permitiendo que el usuario modifique su perfil,
 * fecha de cumpleaños, gustos, recuerdos o publicaciones sociales
 * EXCLUSIVAMENTE HABLANDO con Fifo, sin tocar la app.
 */
class ProfileDatabaseSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "ProfileDBSkill"
    }

    override val name: String = "update_profile_and_tastes"

    override val description: String =
        "Actualiza los datos del usuario en la base de datos cuando menciona su cumpleaños, año de nacimiento, ciudad, nombre, o cuando aprende un nuevo gusto o pide publicar algo."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["update_demographics", "add_taste", "remove_taste", "publish_post", "add_memory"],
                "description": "Tipo de mutación a realizar en la base de datos"
            },
            "full_name": { "type": "string", "description": "Nombre completo" },
            "birth_date": { "type": "string", "description": "Fecha de cumpleaños (ej: '14 de Mayo, 1958')" },
            "birth_year": { "type": "integer", "description": "Año de nacimiento (ej: 1958)" },
            "gender": { "type": "string", "description": "Género o trato ('Mujer', 'Hombre', etc.)" },
            "city": { "type": "string", "description": "Ciudad de residencia" },
            "taste_name": { "type": "string", "description": "Nombre del gusto o pasatiempo para agregar o quitar" },
            "post_content": { "type": "string", "description": "Texto para compartir en la comunidad Fifo Amigos" },
            "post_category": { "type": "string", "description": "Categoría de la publicación" }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = args["action"]?.toString()?.trim() ?: "update_demographics"
        Log.i(TAG, "Ejecutando acción de base de datos: $action con args: $args")

        return when (action) {
            "update_demographics" -> {
                val fullName = args["full_name"]?.toString()
                val birthDate = args["birth_date"]?.toString()
                val birthYear = args["birth_year"]?.toString()?.toIntOrNull()
                val gender = args["gender"]?.toString()
                val city = args["city"]?.toString()

                FifoDataRepository.updateDemographics(
                    fullName = fullName,
                    birthDate = birthDate,
                    birthYear = birthYear,
                    gender = gender,
                    city = city
                )

                val user = FifoDataRepository.userProfile.value
                val feedback = buildString {
                    append("He actualizado tus datos en tu perfil. ")
                    if (birthDate != null || birthYear != null) {
                        append("Guardé tu cumpleaños para el ${user.birthDate}. ")
                    }
                    if (city != null) {
                        append("Registré que vives en ${user.city}. ")
                    }
                }
                SkillResult(success = true, spokenFeedback = feedback.ifBlank { "Datos de perfil actualizados." })
            }

            "add_taste" -> {
                val taste = args["taste_name"]?.toString()?.trim() ?: ""
                if (taste.isNotBlank()) {
                    val added = FifoDataRepository.addTaste(taste)
                    val spoken = if (added) {
                        "¡Qué lindo pasatiempo! Ya agregué $taste a tus gustos en tu perfil."
                    } else {
                        "Ya tenía anotado $taste en tus gustos."
                    }
                    SkillResult(success = true, spokenFeedback = spoken)
                } else {
                    SkillResult(success = false, spokenFeedback = "No alcancé a captar el nombre del gusto.")
                }
            }

            "remove_taste" -> {
                val taste = args["taste_name"]?.toString()?.trim() ?: ""
                val removed = FifoDataRepository.removeTaste(taste)
                val spoken = if (removed) {
                    "Listo, quité $taste de tus intereses en la aplicación."
                } else {
                    "No encontré $taste en tu lista de intereses."
                }
                SkillResult(success = true, spokenFeedback = spoken)
            }

            "publish_post" -> {
                val content = args["post_content"]?.toString()?.trim() ?: ""
                val category = args["post_category"]?.toString()?.trim() ?: "Bienestar"
                if (content.isNotBlank()) {
                    FifoDataRepository.addSocialPost(content, category)
                    SkillResult(
                        success = true,
                        spokenFeedback = "He publicado tu mensaje en el muro de Fifo Amigos para que tus compañeros puedan leerte y dejarte cariño."
                    )
                } else {
                    SkillResult(success = false, spokenFeedback = "No recibí el texto para publicar.")
                }
            }

            "add_memory" -> {
                val title = args["taste_name"] ?: args["post_content"] ?: "Recuerdo"
                FifoDataRepository.addMemory("🌸", title.toString(), "Compartido con cariño en la conversación.")
                SkillResult(success = true, spokenFeedback = "Guardé este lindo recuerdo en tu perfil.")
            }

            else -> {
                SkillResult(success = true, spokenFeedback = "He registrado los cambios en tu perfil.")
            }
        }
    }
}
