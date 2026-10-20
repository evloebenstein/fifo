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
        val userId = FifoDataRepository.userProfile.value.id
        Log.i(TAG, "Ejecutando PhoneCallSkill con acción: $action")

        return when (action) {
            "answer" -> {
                val callInfo = FifoDataRepository.incomingCall.value ?: FifoDataRepository.lastActiveCall
                val answered = FifoDataRepository.answerCurrentCall()
                if (answered) {
                    val rawCaller = callInfo?.callerName ?: ""
                    val phone = callInfo?.phoneNumber ?: ""

                    // 1. Identificar interlocutor localmente en el grafo relacional
                    val relContact = if (rawCaller.isNotBlank()) FifoDataRepository.findContactByRoleOrName(rawCaller) else null
                    val contactName = relContact?.contactName ?: rawCaller.ifBlank { "la llamada" }
                    val role = relContact?.relationshipRole ?: ""

                    // 2. Consultar / catalogar en el backend si hay ID de usuario disponible
                    val backendIdentity = if (userId.isNotBlank()) {
                        com.fifo.voicepipeline.network.FifoBackendClient.identifyContact(userId, rawCaller, phone)
                    } else null

                    val finalName = backendIdentity?.contactName ?: contactName
                    val finalRole = backendIdentity?.relationshipRole?.takeIf { it.isNotBlank() && it != "Contacto telefónico" } ?: role
                    val recentTopics = backendIdentity?.recentTopics ?: ""
                    val followup = backendIdentity?.suggestedFollowup ?: "Atender tranquilamente la llamada."

                    // 3. Registrar evento en backend de forma asíncrona
                    if (userId.isNotBlank()) {
                        com.fifo.voicepipeline.network.FifoBackendClient.logCallEvent(
                            userId = userId,
                            action = "answer",
                            callerName = finalName,
                            phoneNumber = phone,
                            durationSeconds = 0,
                            notes = "Llamada contestada en altavoz por Fifo"
                        )
                    }

                    // 4. Frase cálida, empática y no genérica
                    val spoken = if (finalRole.isNotBlank() && !finalRole.equals("Contacto", ignoreCase = true)) {
                        "Contestando la llamada de su $finalRole $finalName en altavoz para que hable con tranquilidad."
                    } else if (finalName != "la llamada") {
                        "Contestando a $finalName en altavoz."
                    } else {
                        "Contestando la llamada entrante en altavoz para usted."
                    }

                    SkillResult(
                        success = true,
                        spokenFeedback = spoken,
                        data = mapOf(
                            "action" to "answer",
                            "status" to "answered",
                            "caller" to finalName,
                            "caller_name" to finalName,
                            "phone" to phone,
                            "relationship" to finalRole,
                            "recent_context" to recentTopics,
                            "suggested_followup" to followup
                        )
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
                val callInfo = FifoDataRepository.incomingCall.value ?: FifoDataRepository.lastActiveCall
                FifoDataRepository.hangupCurrentCall()
                val rawCaller = callInfo?.callerName ?: ""
                val phone = callInfo?.phoneNumber ?: ""

                // 1. Identificar interlocutor localmente
                val relContact = if (rawCaller.isNotBlank()) FifoDataRepository.findContactByRoleOrName(rawCaller) else null
                val contactName = relContact?.contactName ?: rawCaller.ifBlank { "la llamada" }
                val role = relContact?.relationshipRole ?: ""

                // 2. Consultar o actualizar catalogación en backend
                val backendIdentity = if (userId.isNotBlank() && rawCaller.isNotBlank()) {
                    com.fifo.voicepipeline.network.FifoBackendClient.identifyContact(userId, rawCaller, phone)
                } else null

                val finalName = backendIdentity?.contactName ?: contactName
                val finalRole = backendIdentity?.relationshipRole?.takeIf { it.isNotBlank() } ?: role
                val recentTopics = backendIdentity?.recentTopics ?: ""

                // 3. Registrar evento en backend
                if (userId.isNotBlank()) {
                    com.fifo.voicepipeline.network.FifoBackendClient.logCallEvent(
                        userId = userId,
                        action = "hangup",
                        callerName = finalName,
                        phoneNumber = phone,
                        durationSeconds = 0,
                        notes = "Llamada finalizada por Fifo"
                    )
                }

                // 4. Frase conversacional cálida que previene respuestas secas o enlatadas
                val spoken = if (finalName != "la llamada" && finalName.isNotBlank()) {
                    "Listo, he colgado la llamada con $finalName. Si desea que le recuerde o anote algún recado de lo que hablaron, con gusto lo guardo."
                } else {
                    "He finalizado la llamada. Avíseme si necesita que registre algún recordatorio o nota de la conversación."
                }

                SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf(
                        "action" to "hangup",
                        "status" to "hung_up",
                        "caller" to finalName,
                        "caller_name" to finalName,
                        "relationship" to finalRole,
                        "recent_context" to recentTopics,
                        "suggested_followup" to "Preguntar con amabilidad si desea guardar un recordatorio o recado de la llamada"
                    )
                )
            }

            "status" -> {
                val callInfo = FifoDataRepository.incomingCall.value
                if (callInfo != null && callInfo.isRinging) {
                    val rawCaller = callInfo.callerName
                    val relContact = FifoDataRepository.findContactByRoleOrName(rawCaller)
                    val backendIdentity = if (userId.isNotBlank()) {
                        com.fifo.voicepipeline.network.FifoBackendClient.identifyContact(userId, rawCaller, callInfo.phoneNumber)
                    } else null

                    val finalName = backendIdentity?.contactName ?: (relContact?.contactName ?: rawCaller)
                    val finalRole = backendIdentity?.relationshipRole?.takeIf { it.isNotBlank() && it != "Contacto telefónico" } ?: (relContact?.relationshipRole ?: "")

                    val spoken = if (finalRole.isNotBlank()) {
                        "Tiene una llamada entrante de su $finalRole $finalName. ¿Desea que le conteste en altavoz o que cuelgue?"
                    } else {
                        "Tiene una llamada entrante de $finalName. ¿Desea que conteste en altavoz o prefiere colgar?"
                    }

                    SkillResult(
                        success = true,
                        spokenFeedback = spoken,
                        data = mapOf(
                            "ringing" to true,
                            "caller" to finalName,
                            "caller_name" to finalName,
                            "relationship" to finalRole,
                            "phone" to callInfo.phoneNumber
                        )
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
                    else -> ""
                }

                // Si no es un servicio de emergencia nacional, consultar el Grafo Relacional de Fifo
                if (phoneNumber.isBlank()) {
                    val relContact = FifoDataRepository.findContactByRoleOrName(contactInput)
                    if (relContact != null && !relContact.phoneNumber.isNullOrBlank()) {
                        resolvedContactName = "${relContact.contactName} (${relContact.relationshipRole})"
                        phoneNumber = relContact.phoneNumber
                    }
                }

                // Si no se encontró en el grafo relacional, buscar en la libreta del sistema telefónico
                if (phoneNumber.isBlank()) {
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

                if (userId.isNotBlank()) {
                    com.fifo.voicepipeline.network.FifoBackendClient.logCallEvent(
                        userId = userId,
                        action = "dialed",
                        callerName = resolvedContactName,
                        phoneNumber = phoneNumber,
                        durationSeconds = 0,
                        notes = "Marcado saliente solicitado por voz: $contactInput"
                    )
                }

                val spoken = if (phoneNumber.length <= 3) {
                    "Marcando inmediatamente al servicio de urgencia $resolvedContactName."
                } else {
                    "Marcando a $resolvedContactName por teléfono. Ya tiene la llamada lista en pantalla."
                }

                SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf(
                        "action" to "dialed",
                        "target" to contactInput,
                        "name" to resolvedContactName,
                        "phone" to phoneNumber
                    )
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
