package com.fifo.voicepipeline.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository

/**
 * Skill que permite a Fifo gestionar llamadas telefónicas por comandos de voz:
 * - Contestar llamadas entrantes en altavoz ("Fifo contesta", "atiende")
 * - Colgar o rechazar llamadas ("Fifo cuelga", "detener llamada")
 * - Saber quién está llamando ("Fifo quién llama")
 * - Marcar llamadas a contactos ("Fifo llama a mi hija")
 */
class PhoneCallSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "PhoneCallSkill"
    }

    override val name: String = "manage_phone_call"

    override val description: String =
        "Gestiona llamadas telefónicas del celular por voz: contesta llamadas entrantes en altavoz ('answer'), cuelga o rechaza llamadas ('hangup'), consulta quién está llamando ('status') o inicia una llamada hacia un contacto ('call')."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["answer", "hangup", "status", "call"],
                "description": "Acción a realizar con la llamada: 'answer' (contestar), 'hangup' (colgar/rechazar), 'status' (quién llama), 'call' (iniciar llamada)"
            },
            "contact_name": {
                "type": "string",
                "description": "Nombre de la persona o servicio de emergencia cuando la acción es 'call' (ej: 'hija Carmen', 'SAMU 131')"
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = args["action"]?.toString()?.lowercase()?.trim() ?: "status"
        Log.i(TAG, "Ejecutando PhoneCallSkill con acción: $action")

        return when (action) {
            "answer" -> {
                val callInfo = FifoDataRepository.incomingCall.value
                val answered = FifoDataRepository.answerCurrentCall()
                if (answered) {
                    val caller = callInfo?.callerName ?: "la llamada"
                    SkillResult(
                        success = true,
                        spokenFeedback = "Contestando la llamada de $caller en altavoz para usted.",
                        data = mapOf("action" to "answer", "status" to "answered")
                    )
                } else {
                    SkillResult(
                        success = false,
                        spokenFeedback = "No hay ninguna llamada sonando en este momento o se contestó desde el teléfono.",
                        data = mapOf("action" to "answer", "status" to "no_call")
                    )
                }
            }

            "hangup" -> {
                val callInfo = FifoDataRepository.incomingCall.value
                val hungUp = FifoDataRepository.hangupCurrentCall()
                val caller = callInfo?.callerName ?: "la llamada"
                SkillResult(
                    success = true,
                    spokenFeedback = "He detenido y colgado la llamada de $caller.",
                    data = mapOf("action" to "hangup", "status" to "hung_up")
                )
            }

            "status" -> {
                val callInfo = FifoDataRepository.incomingCall.value
                if (callInfo != null && callInfo.isRinging) {
                    SkillResult(
                        success = true,
                        spokenFeedback = "Tiene una llamada entrante de ${callInfo.callerName}. ¿Desea que conteste o que cuelgue?",
                        data = mapOf("ringing" to true, "caller" to callInfo.callerName, "phone" to callInfo.phoneNumber)
                    )
                } else {
                    SkillResult(
                        success = true,
                        spokenFeedback = "En este momento no tiene ninguna llamada sonando.",
                        data = mapOf("ringing" to false)
                    )
                }
            }

            "call" -> {
                val contactInput = args["contact_name"]?.toString()?.trim() ?: "contacto de apoyo"
                val profile = FifoDataRepository.userProfile.value
                val lower = contactInput.lowercase()

                var resolvedContactName = profile.emergencyContactName
                var phoneNumber = when {
                    lower.contains("samu") || lower.contains("131") || lower.contains("ambulancia") -> {
                        resolvedContactName = "Ambulancia SAMU 131"
                        "131"
                    }
                    lower.contains("bombero") || lower.contains("132") -> {
                        resolvedContactName = "Bomberos 132"
                        "132"
                    }
                    lower.contains("carabinero") || lower.contains("133") -> {
                        resolvedContactName = "Carabineros 133"
                        "133"
                    }
                    lower.contains("carmen") || lower.contains("hija") || lower.contains("familia") -> {
                        resolvedContactName = profile.emergencyContactName
                        profile.emergencyContactPhone
                    }
                    else -> ""
                }

                if (phoneNumber.isBlank()) {
                    // Buscar en contactos del teléfono
                    try {
                        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                        val projection = arrayOf(
                            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                        )
                        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                        val selectionArgs = arrayOf("%$contactInput%")
                        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                                val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                                if (nameIdx >= 0) resolvedContactName = cursor.getString(nameIdx)
                                if (numIdx >= 0) phoneNumber = cursor.getString(numIdx)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error buscando en contactos: ${e.message}")
                    }

                    if (phoneNumber.isBlank()) {
                        resolvedContactName = profile.emergencyContactName
                        phoneNumber = profile.emergencyContactPhone
                    }
                }

                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$phoneNumber")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dialIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error iniciando llamada: ${e.message}")
                }

                val spoken = if (phoneNumber.length <= 3) {
                    "Marcando inmediatamente al servicio de urgencia $resolvedContactName."
                } else {
                    "Marcando a $resolvedContactName por teléfono."
                }

                SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf("target" to contactInput, "name" to resolvedContactName, "phone" to phoneNumber)
                )
            }

            else -> {
                SkillResult(
                    success = false,
                    spokenFeedback = "No pude determinar la acción de llamada solicitada."
                )
            }
        }
    }
}
