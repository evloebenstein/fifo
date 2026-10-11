package com.fifo.voicepipeline.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository

/**
 * Skill para comunicación de emergencia y tranquilidad familiar:
 * llama a familiares o a servicios de urgencia mediante el marcador telefónico nativo.
 */
class PhoneCommunicationSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "PhoneSkill"
    }

    override val name: String = "call_contact"

    override val description: String =
        "Inicia una llamada telefónica para comunicarse con un familiar (ej: hija Carmen, hijo) o a un número de urgencias (131 SAMU, 132 Bomberos, 133 Carabineros)."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "contact_name_or_role": {
                "type": "string",
                "description": "Nombre de la persona o servicio de emergencia (ej: 'hija Carmen', 'SAMU 131', 'médico')"
            }
        },
        "required": ["contact_name_or_role"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val target = args["contact_name_or_role"]?.toString()?.trim() ?: "contacto de apoyo"
        val lower = target.lowercase()

        val profile = FifoDataRepository.userProfile.value
        val phoneNumber = when {
            lower.contains("samu") || lower.contains("ambulancia") || lower.contains("131") -> "131"
            lower.contains("bombero") || lower.contains("132") -> "132"
            lower.contains("carabinero") || lower.contains("policia") || lower.contains("133") -> "133"
            (profile.emergencyContactName.isNotBlank() && lower.contains(profile.emergencyContactName.lowercase().trim())) ||
            lower.contains("emergencia") || lower.contains("familiar") || lower.contains("hija") || lower.contains("hijo") || lower.contains("contacto") -> profile.emergencyContactPhone
            else -> profile.emergencyContactPhone
        }

        Log.i(TAG, "Marcando llamada a: '$target' ($phoneNumber)")

        try {
            val callIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando llamada: ${e.message}")
        }

        val spoken = if (phoneNumber.length <= 3) {
            "Conectando de inmediato con el servicio de urgencia $phoneNumber en su teléfono."
        } else {
            "Marcando a su contacto ${profile.emergencyContactName} por teléfono. Ya tiene la llamada lista en pantalla."
        }

        return SkillResult(
            success = true,
            spokenFeedback = spoken,
            data = mapOf("target" to target, "phone" to phoneNumber)
        )
    }
}
