package com.fifo.voicepipeline.skills

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.fifo.voicepipeline.data.FifoDataRepository

/**
 * Skill para acceder y consultar los contactos reales del teléfono celular:
 * - Responder si Fifo puede ver los contactos.
 * - Listar nombres de los contactos guardados en la libreta.
 * - Buscar el número de teléfono de una persona específica.
 */
class ContactsSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "ContactsSkill"

        /**
         * Formatea un número de teléfono telefónico para que el sintetizador de voz (TTS)
         * lo dicte de forma pausada, clara y natural (con pausas de comas entre grupos de números),
         * ideal para adultos mayores.
         */
        fun formatPhoneNumberForSpeech(phone: String?): String {
            if (phone.isNullOrBlank()) return "no registrado"
            val digits = phone.filter { it.isDigit() || it == '+' }
            if (digits.length <= 4) return digits

            return if (digits.startsWith("+569")) {
                val rest = digits.substring(4)
                if (rest.length >= 8) {
                    "+56 9, ${rest.substring(0, 4)}, ${rest.substring(4)}"
                } else {
                    "+56 9, ${rest.chunked(2).joinToString(", ")}"
                }
            } else if (digits.startsWith("+56")) {
                val rest = digits.substring(3)
                "+56, ${rest.chunked(2).joinToString(", ")}"
            } else if (digits.startsWith("+")) {
                val code = digits.take(3)
                val rest = digits.substring(3)
                "$code, ${rest.chunked(2).joinToString(", ")}"
            } else {
                digits.chunked(2).joinToString(", ")
            }
        }
    }

    override val name: String = "read_phone_contacts"

    override val description: String =
        "Permite a Fifo acceder, dictar y consultar la libreta de contactos del teléfono: dictar el número de una persona, listar contactos guardados o comprobar permisos."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["list", "search", "dictate", "check_permission"],
                "description": "Acción a realizar: 'dictate' o 'search' (dictar o buscar el número de teléfono de una persona por nombre o parentesco), 'list' (listar contactos guardados), o 'check_permission' (comprobar permiso)"
            },
            "query": {
                "type": "string",
                "description": "Nombre o parentesco de la persona a dictar o buscar (ej: 'Pedro', 'Carmen', 'mamá', 'doctor', 'Tomás')"
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = args["action"]?.toString()?.lowercase()?.trim() ?: "list"
        val query = args["query"]?.toString()?.trim() ?: ""

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        // Si tenemos permiso, asegurar que la libreta esté sincronizada en el Grafo Relacional
        if (hasPermission && FifoDataRepository.relationalContacts.value.size <= 4) {
            FifoDataRepository.syncDeviceContactsToRelationalGraph(context)
        }

        // 1. Acciones de búsqueda y dictado telefónico
        if ((action == "search" || action == "dictate") && query.isNotBlank()) {
            // A. Buscar primero en el Grafo Relacional de Fifo (que incluye contactos reales + relaciones aprendidas)
            val relContact = FifoDataRepository.findContactByRoleOrName(query)
            if (relContact != null && !relContact.phoneNumber.isNullOrBlank()) {
                val spokenPhone = formatPhoneNumberForSpeech(relContact.phoneNumber)
                val rolePart = if (relContact.relationshipRole != "Contacto del teléfono") " (${relContact.relationshipRole.lowercase()})" else ""
                return SkillResult(
                    success = true,
                    spokenFeedback = "${relContact.contactName}$rolePart: $spokenPhone.",
                    data = mapOf(
                        "name" to relContact.contactName,
                        "role" to relContact.relationshipRole,
                        "phone" to relContact.phoneNumber,
                        "formatted_phone" to spokenPhone,
                        "closeness" to relContact.closenessScore,
                        "memory" to relContact.contextualMemory
                    )
                )
            }

            // B. Si tiene permiso de Android, buscar en la libreta física del celular por nombre
            if (hasPermission) {
                val deviceContact = searchContact(query)
                if (deviceContact != null) {
                    val spokenPhone = formatPhoneNumberForSpeech(deviceContact.second)
                    // Guardar en el grafo relacional para futuras charlas
                    FifoDataRepository.addOrUpdateRelationalContact(
                        com.fifo.voicepipeline.ui.model.RelationalContact(
                            id = "ctc_phone_${deviceContact.first.take(15).lowercase()}_${System.currentTimeMillis() % 10000}",
                            contactName = deviceContact.first,
                            phoneNumber = deviceContact.second,
                            relationshipRole = "Contacto del teléfono",
                            closenessScore = 0.55f,
                            contextualMemory = "Contacto guardado en el teléfono celular."
                        )
                    )
                    return SkillResult(
                        success = true,
                        spokenFeedback = "${deviceContact.first}: $spokenPhone.",
                        data = mapOf(
                            "name" to deviceContact.first,
                            "phone" to deviceContact.second,
                            "formatted_phone" to spokenPhone
                        )
                    )
                }
            }

            // C. Revisar si coincide con el contacto de emergencia registrado en el perfil
            val profile = FifoDataRepository.userProfile.value
            val emergName = profile.emergencyContactName.lowercase().trim()
            val isEmergencyMatch = (emergName.isNotBlank() && query.contains(emergName, ignoreCase = true)) ||
                    query.contains("emergencia", ignoreCase = true) ||
                    query.contains("apoyo", ignoreCase = true)

            if (isEmergencyMatch && profile.emergencyContactPhone.isNotBlank()) {
                val spokenPhone = formatPhoneNumberForSpeech(profile.emergencyContactPhone)
                return SkillResult(
                    success = true,
                    spokenFeedback = "${profile.emergencyContactName}: $spokenPhone.",
                    data = mapOf("name" to profile.emergencyContactName, "phone" to profile.emergencyContactPhone)
                )
            }

            return SkillResult(
                success = false,
                spokenFeedback = "No se encontró a '$query' en contactos.",
                data = mapOf("query" to query, "found" to false)
            )
        }

        if (!hasPermission) {
            val relContacts = FifoDataRepository.relationalContacts.value
            if (action == "list" && relContacts.isNotEmpty()) {
                val names = relContacts.take(4).joinToString(", ") { "${it.contactName} (${it.relationshipRole})" }
                return SkillResult(
                    success = true,
                    spokenFeedback = "Contactos: $names.",
                    data = mapOf("relational_contacts" to relContacts.map { it.contactName })
                )
            }
            return SkillResult(
                success = false,
                spokenFeedback = "Permiso de contactos no concedido.",
                data = mapOf("has_permission" to false)
            )
        }

        return when (action) {
            "check_permission" -> {
                SkillResult(
                    success = true,
                    spokenFeedback = "Permiso de contactos concedido.",
                    data = mapOf("has_permission" to true)
                )
            }

            else -> { // "list"
                val contacts = getAllContacts(limit = 8)
                val totalCount = getTotalContactCount()
                val relContacts = FifoDataRepository.relationalContacts.value
                val sampleNames = if (relContacts.isNotEmpty()) {
                    relContacts.take(4).joinToString(", ") { "${it.contactName} (${it.relationshipRole})" }
                } else if (contacts.isNotEmpty()) {
                    contacts.take(4).joinToString(", ") { it.first }
                } else {
                    "nadie aún"
                }

                val spoken = "Contactos ($totalCount): $sampleNames."

                SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf("count" to (if (totalCount > 0) totalCount else relContacts.size), "sample" to sampleNames)
                )
            }
        }
    }

    private fun searchContact(searchTerm: String): Pair<String, String>? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$searchTerm%")

            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else searchTerm
                    val phone = if (numIdx >= 0) cursor.getString(numIdx) else ""
                    Pair(name, phone)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando contacto '$searchTerm': ${e.message}")
            null
        }
    }

    private fun getAllContacts(limit: Int = 10): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val seenNames = mutableSetOf<String>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && list.size < limit) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                    val phone = if (numIdx >= 0) cursor.getString(numIdx) else ""
                    if (name.isNotBlank() && seenNames.add(name.lowercase())) {
                        list.add(Pair(name, phone))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listando contactos: ${e.message}")
        }
        return list
    }

    private fun getTotalContactCount(): Int {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone._ID), null, null, null)?.use {
                it.count
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
