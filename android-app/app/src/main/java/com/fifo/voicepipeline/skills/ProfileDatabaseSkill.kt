package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository

/**
 * Skill que ejecuta mutaciones directamente sobre la base de datos de Fifo
 * ([FifoDataRepository]), permitiendo que el usuario modifique su perfil,
 * fecha de cumpleaños, gustos, recuerdos o publicaciones sociales
 * EXCLUSIVAMENTE HABLANDO con Fifo, sin tocar la app.
 *
 * Implementa estrictas reglas de privacidad y protección al adulto mayor:
 * 1. Solo registra intereses, pasatiempos recreativos, recuerdos afectivos y anécdotas positivas.
 * 2. Datos sensibles (salud, médicos, conflictos familiares) requieren consentimiento verbal claro.
 * 3. TOTALMENTE PROHIBIDOS temas con connotación sexual en perfiles o publicaciones.
 * 4. Protocolo de salvaguarda ante revelación de abuso/violencia (asistencia y contención con consentimiento).
 */
class ProfileDatabaseSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "ProfileDBSkill"

        // Palabras o patrones de connotación sexual prohibidos
        private val SEXUAL_PROHIBITED_REGEX = Regex(
            "(?i)\\b(sexual|sexo|erótico|erotica|erotismo|porno|pornografía|desnudez|genitales|coito)\\b"
        )

        // Palabras de abuso o maltrato que activan el protocolo de salvaguarda
        private val ABUSE_SAFEGUARD_REGEX = Regex(
            "(?i)\\b(abuso|abusó|violó|violacion|violación|me tocan|me tocó|me pegan|me golpean|me maltratan|violencia|agresión física|acoso|maltrato)\\b"
        )

        // Palabras de información médica o sensible delicada
        private val SENSITIVE_HEALTH_REGEX = Regex(
            "(?i)\\b(cáncer|cancer|cirugía|cirugia|quimioterapia|enfermedad terminal|diagnóstico grave|hospitalizado|urgencia médica|herencia disputada|demanda judicial)\\b"
        )
    }

    override val name: String = "update_profile_and_tastes"

    override val description: String =
        "Actualiza los datos del usuario en la base de datos (cumpleaños, gustos positivos, pasatiempos, recuerdos o publicaciones). Aplica reglas estrictas de privacidad y consentimiento."

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
            "taste_name": { "type": "string", "description": "Nombre del gusto o pasatiempo recreativo positivo" },
            "memory_title": { "type": "string", "description": "Título del recuerdo, plan de entrenamiento o nota (ej: 'Plan de Jiu-Jitsu semanal', 'Receta')" },
            "memory_description": { "type": "string", "description": "Detalle completo del plan de entrenamiento o recuerdo para guardarlo en la pestaña de recuerdos" },
            "memory_icon": { "type": "string", "description": "Emoji representativo (ej: '🥋' para entreno, '📝' para plan, '🌸' para recuerdo)" },
            "post_content": { "type": "string", "description": "Texto para compartir en la comunidad Fifo Amigos" },
            "post_category": { "type": "string", "description": "Categoría de la publicación" },
            "has_explicit_consent": {
                "type": "boolean",
                "description": "True si el usuario dio verbalmente su consentimiento explícito para publicar un tema sensible de salud o familia"
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = args["action"]?.toString()?.trim() ?: "update_demographics"
        val hasConsent = args["has_explicit_consent"] == true ||
                args["has_explicit_consent"]?.toString()?.equals("true", ignoreCase = true) == true

        Log.i(TAG, "Ejecutando acción de base de datos: $action con args: $args (consent: $hasConsent)")

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
                    append("He actualizado sus datos en su perfil. ")
                    if (birthDate != null || birthYear != null) {
                        append("Guardé su cumpleaños para el ${user.birthDate}. ")
                    }
                    if (city != null) {
                        append("Registré que vive en ${user.city}. ")
                    }
                }
                SkillResult(success = true, spokenFeedback = feedback.ifBlank { "Datos de perfil actualizados." })
            }

            "add_taste" -> {
                val taste = args["taste_name"]?.toString()?.trim() ?: ""
                if (taste.isBlank()) {
                    return SkillResult(success = false, spokenFeedback = "No alcancé a captar el nombre del gusto.")
                }

                // 1. Verificación de salvaguarda ante abuso
                if (ABUSE_SAFEGUARD_REGEX.containsMatchIn(taste)) {
                    Log.w(TAG, "Alerta de salvaguarda detectada en solicitud de gusto: $taste")
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Lamento profundamente lo que me cuenta y su seguridad es primordial. ¿Me autoriza a contactar a su familiar de confianza o a un canal confidencial de ayuda para protegerle?"
                    )
                }

                // 2. Filtro estricto: Prohibición total de temas connotados sexualmente
                if (SEXUAL_PROHIBITED_REGEX.containsMatchIn(taste)) {
                    Log.w(TAG, "Rechazo de contenido sexual en taste: $taste")
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Por respeto y cuidado de su privacidad, ese tema no se puede registrar en sus gustos de la aplicación."
                    )
                }

                // 3. Filtro de temas médicos o sensibles delicados
                if (SENSITIVE_HEALTH_REGEX.containsMatchIn(taste)) {
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Solo registro pasatiempos e intereses positivos. La información de salud la cuidamos en estricta privacidad."
                    )
                }

                // Registro positivo exitoso
                val added = FifoDataRepository.addTaste(taste)
                val spoken = if (added) {
                    "¡Qué lindo pasatiempo! Ya agregué $taste a sus gustos en su perfil."
                } else {
                    "Ya tenía anotado $taste en sus gustos."
                }
                SkillResult(success = true, spokenFeedback = spoken)
            }

            "remove_taste" -> {
                val taste = args["taste_name"]?.toString()?.trim() ?: ""
                val removed = FifoDataRepository.removeTaste(taste)
                val spoken = if (removed) {
                    "Listo, quité $taste de sus intereses en la aplicación."
                } else {
                    "No encontré $taste en su lista de intereses."
                }
                SkillResult(success = true, spokenFeedback = spoken)
            }

            "publish_post" -> {
                val content = args["post_content"]?.toString()?.trim() ?: ""
                val category = args["post_category"]?.toString()?.trim() ?: "Bienestar"

                if (content.isBlank()) {
                    return SkillResult(success = false, spokenFeedback = "No recibí el texto para publicar.")
                }

                // 1. Salvaguarda absoluta ante abuso o violencia (tolerancia cero a publicación pública)
                if (ABUSE_SAFEGUARD_REGEX.containsMatchIn(content)) {
                    Log.w(TAG, "Bloqueo de publicación comunitaria por salvaguarda de abuso: $content")
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Lamento mucho lo que está viviendo. Esta situación no debe publicarse en un muro abierto; su bienestar y protección son lo más importante. Con su permiso, ¿desea que me comunique con su familiar de confianza o con una línea de apoyo?"
                    )
                }

                // 2. Prohibición total de contenidos con connotación sexual
                if (SEXUAL_PROHIBITED_REGEX.containsMatchIn(content)) {
                    Log.w(TAG, "Bloqueo de publicación por contenido con connotación sexual: $content")
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Por política de respeto y cuidado comunitario, no está permitido publicar contenidos de connotación sexual en la comunidad."
                    )
                }

                // 3. Verificación de temas de salud o familiares sensibles -> Requiere consentimiento explícito
                val isSensitive = SENSITIVE_HEALTH_REGEX.containsMatchIn(content)
                if (isSensitive && !hasConsent) {
                    Log.i(TAG, "Contenido de salud sensible requiere consentimiento explícito: $content")
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Esta es información de salud delicada. Para proteger su privacidad, solo puedo compartirla si me confirma expresamente diciendo: 'Fifo, sí deseo publicar mi tema de salud'. ¿Prefiere mantenerla en privado entre nosotros dos?"
                    )
                }

                // Publicación autorizada
                FifoDataRepository.addSocialPost(content, category)
                val feedback = if (isSensitive && hasConsent) {
                    "He publicado su mensaje en el muro de Fifo Amigos con su autorización y consentimiento expreso."
                } else {
                    "He publicado su mensaje en el muro de Fifo Amigos para que sus compañeros puedan leerle y dejarle cariño."
                }
                SkillResult(success = true, spokenFeedback = feedback)
            }

            "add_memory" -> {
                val title = args["memory_title"]?.toString()
                    ?: args["taste_name"]?.toString()
                    ?: args["post_content"]?.toString()
                    ?: "Recuerdo"
                val description = args["memory_description"]?.toString()
                    ?: "Guardado en tus conversaciones y recuerdos de Fifo."
                val icon = args["memory_icon"]?.toString()
                    ?: if (title.lowercase().contains("jiu") || title.lowercase().contains("entren") || title.lowercase().contains("deport") || title.lowercase().contains("ejercicio")) "🥋" else "🌸"

                // Filtro de salvaguarda ante abuso
                if (ABUSE_SAFEGUARD_REGEX.containsMatchIn(title) || ABUSE_SAFEGUARD_REGEX.containsMatchIn(description)) {
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Comprendo lo difícil de esto y estoy aquí para apoyarle. ¿Me autoriza a contactar a su familiar o a un canal confidencial de ayuda?"
                    )
                }

                // Filtro sexual
                if (SEXUAL_PROHIBITED_REGEX.containsMatchIn(title) || SEXUAL_PROHIBITED_REGEX.containsMatchIn(description)) {
                    return SkillResult(
                        success = false,
                        spokenFeedback = "Por privacidad, no se registran temas de esa índole en el perfil."
                    )
                }

                FifoDataRepository.addMemory(icon, title, description)
                SkillResult(
                    success = true,
                    spokenFeedback = "He guardado '$title' en tus recuerdos de Fifo para que puedas revisarlo cuando quieras en la app.",
                    data = mapOf("title" to title, "icon" to icon)
                )
            }

            else -> {
                SkillResult(success = true, spokenFeedback = "He registrado los cambios en su perfil.")
            }
        }
    }
}
