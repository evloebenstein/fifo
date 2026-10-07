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
    }

    override val name: String = "read_phone_contacts"

    override val description: String =
        "Permite a Fifo acceder y consultar la libreta de contactos del teléfono: comprobar si puede verlos, listar personas guardadas o buscar el número de teléfono de un familiar, amigo, conocido o médico."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["list", "search", "check_permission"],
                "description": "Acción a realizar: 'list' (listar contactos para saber a quiénes puede llamar), 'search' (buscar el número de teléfono de una persona por nombre), o 'check_permission' (comprobar si Fifo tiene permiso de ver contactos)"
            },
            "query": {
                "type": "string",
                "description": "Nombre de la persona a buscar si action='search' (ej: 'Pedro', 'Carmen', 'mamá', 'doctor')"
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

        if (!hasPermission) {
            return SkillResult(
                success = false,
                spokenFeedback = "Para poder ver tus contactos del teléfono, necesito que me concedas el permiso de Contactos en la aplicación.",
                data = mapOf("has_permission" to false)
            )
        }

        return when (action) {
            "check_permission" -> {
                SkillResult(
                    success = true,
                    spokenFeedback = "Sí, tengo permiso completo para ver los contactos de tu teléfono y ayudarte a llamar a quien necesites.",
                    data = mapOf("has_permission" to true)
                )
            }

            "search" -> {
                if (query.isBlank()) {
                    SkillResult(
                        success = false,
                        spokenFeedback = "¿A qué persona te gustaría que busque en tus contactos?"
                    )
                } else {
                    val result = searchContact(query)
                    if (result != null) {
                        SkillResult(
                            success = true,
                            spokenFeedback = "Encontré a ${result.first} en tus contactos con el número ${result.second}. ¿Quieres que le marque por teléfono?",
                            data = mapOf("name" to result.first, "phone" to result.second)
                        )
                    } else {
                        // Revisar si coincide con el contacto de emergencia en perfil
                        val profile = FifoDataRepository.userProfile.value
                        if (query.contains("carmen", ignoreCase = true) || query.contains("hija", ignoreCase = true) || query.contains("emergencia", ignoreCase = true)) {
                            SkillResult(
                                success = true,
                                spokenFeedback = "Tengo registrado a tu contacto de apoyo ${profile.emergencyContactName} con el número ${profile.emergencyContactPhone}. ¿Deseas que le marque?",
                                data = mapOf("name" to profile.emergencyContactName, "phone" to profile.emergencyContactPhone)
                            )
                        } else {
                            SkillResult(
                                success = false,
                                spokenFeedback = "Busqué en tus contactos del celular y no encontré a nadie guardado como '$query'. ¿Quieres que lo busque con otro nombre?",
                                data = mapOf("query" to query, "found" to false)
                            )
                        }
                    }
                }
            }

            else -> { // "list"
                val contacts = getAllContacts(limit = 8)
                val totalCount = getTotalContactCount()
                if (contacts.isNotEmpty()) {
                    val namesSample = contacts.take(4).joinToString(", ") { it.first }
                    val spoken = if (totalCount <= 4) {
                        "Sí, puedo ver tus contactos del teléfono. Tienes a $namesSample. ¿Quieres que llame a alguno de ellos?"
                    } else {
                        "Sí, puedo ver tus contactos del teléfono. Tienes $totalCount contactos guardados, por ejemplo $namesSample, entre otros. ¿Deseas que llame a alguien o busque el teléfono de alguna persona en específico?"
                    }
                    SkillResult(
                        success = true,
                        spokenFeedback = spoken,
                        data = mapOf("total" to totalCount, "sample" to contacts)
                    )
                } else {
                    val profile = FifoDataRepository.userProfile.value
                    SkillResult(
                        success = true,
                        spokenFeedback = "Sí, puedo acceder a tus contactos del teléfono, aunque actualmente tu lista está vacía. Solo tengo registrado a tu contacto de apoyo ${profile.emergencyContactName}.",
                        data = mapOf("total" to 0)
                    )
                }
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
