package com.fifo.voicepipeline.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.fifo.voicepipeline.data.model.RelationalContact

/**
 * Fuente de contactos del teléfono.
 *
 * Es una costura, no un VAD ni un dueño de hardware: solo lee la libreta y
 * devuelve modelos de dominio. Producción usa [AndroidContactSource]; las
 * pruebas inyectan una lista fija sin tocar Android.
 */
fun interface ContactSourcePort {
    /** Devuelve los contactos legibles. Si no hay permiso, devuelve una lista vacía. */
    fun read(): List<RelationalContact>
}

/**
 * Lee la libreta real del teléfono.
 *
 * La normalización y la identidad sintética del contacto se conservan igual
 * que en el camino anterior: nombre visible, teléfono limpio y un `id` estable
 * derivado de ambos.
 */
class AndroidContactSource(
    private val context: Context,
    private val permissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }
) : ContactSourcePort {

    override fun read(): List<RelationalContact> {
        if (!permissionChecker()) return emptyList()

        val contacts = mutableListOf<RelationalContact>()
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
                val seenNames = mutableSetOf<String>()

                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() ?: "" else ""
                    val rawPhone = if (numIdx >= 0) cursor.getString(numIdx)?.trim() ?: "" else ""
                    if (name.isBlank()) continue
                    if (!seenNames.add(name.lowercase())) continue

                    val cleanPhone = rawPhone.replace(Regex("[^0-9+]"), "")
                    val sanitizedId = "ctc_phone_" +
                        name.replace(Regex("[^a-zA-Z0-9]"), "_").take(20).lowercase() +
                        "_" + cleanPhone.takeLast(4)

                    contacts.add(
                        RelationalContact(
                            id = sanitizedId,
                            contactName = name,
                            phoneNumber = cleanPhone.ifBlank { null },
                            relationshipRole = "Contacto del teléfono",
                            closenessScore = 0.50f,
                            trustTier = 3,
                            emotionalValence = "neutral",
                            contextualMemory = "Contacto guardado en la libreta del celular.",
                            mentionCount = 0,
                            lastMentionedAt = "",
                            alias = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AndroidContactSource", "Error leyendo contactos: ${e.message}", e)
        }
        return contacts
    }
}

/**
 * Sincroniza la libreta con el grafo relacional.
 *
 * Solo orquesta: lee por [ContactSourcePort] y entrega al dueño del grafo.
 * No persiste ni fusiona por su cuenta.
 */
class DeviceContactsSync(
    private val source: ContactSourcePort,
    private val graph: com.fifo.voicepipeline.data.modules.RelationalGraphModule
) {
    /** Devuelve cuántos contactos se procesaron (0 si no hay permiso o libreta). */
    fun sync(): Int = graph.ingestContacts(source.read())
}
