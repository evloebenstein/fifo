package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.contacts.ContactMatcher
import com.fifo.voicepipeline.data.contacts.RelationalGraphCoordinator
import com.fifo.voicepipeline.data.local.FifoDatabaseHelper
import com.fifo.voicepipeline.data.memory.MentionedPersonExtraction
import com.fifo.voicepipeline.data.model.ContactAppearance
import com.fifo.voicepipeline.data.model.ContactPhoneHint
import com.fifo.voicepipeline.data.model.FriendRecommendation
import com.fifo.voicepipeline.data.model.RelationalContact
import com.fifo.voicepipeline.network.FifoBackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Costura de publicación del grafo relacional.
 *
 * Producción usa [BackendRelationalRemote]; las pruebas inyectan un no-op para
 * medir fusión y procedencia sin tocar la red.
 */
interface RelationalRemotePort {
    suspend fun postContact(userId: String, contact: RelationalContact)
    suspend fun syncContacts(userId: String, contacts: List<RelationalContact>)
}

/** Adaptador de producción sobre [FifoBackendClient]. */
object BackendRelationalRemote : RelationalRemotePort {
    override suspend fun postContact(userId: String, contact: RelationalContact) {
        FifoBackendClient.postRelationalContact(userId, contact)
    }

    override suspend fun syncContacts(userId: String, contacts: List<RelationalContact>) {
        FifoBackendClient.syncRelationalContactsBatch(userId, contacts)
    }
}

/**
 * Dueño único del grafo relacional local: contactos, apariciones y teléfonos
 * pendientes de confirmar (ADR-0049).
 *
 * La lista reactiva sigue viviendo en [EpisodicMemoryModule] porque la ventana
 * compacta de contexto la consume en el mismo turno. Este módulo es el único
 * que decide fusiones, persiste procedencia y sincroniza; el repositorio solo
 * delega.
 *
 * Lo que **no** hace:
 * - no lee Android (la libreta se entrega ya leída por `ContactSourcePort`);
 * - no inventa parentescos ni teléfonos: eso lo decide [RelationalGraphCoordinator];
 * - no habla con la UI.
 */
class RelationalGraphModule(
    private val memory: EpisodicMemoryModule,
    private val database: () -> FifoDatabaseHelper?,
    private val scope: CoroutineScope,
    private val userId: () -> String,
    private val coordinator: RelationalGraphCoordinator = RelationalGraphCoordinator(),
    private val remote: RelationalRemotePort = BackendRelationalRemote,
    private val nowIso: () -> String = { utcNowIso() }
) {

    val contacts get() = memory.relationalContacts

    fun findContactByRoleOrName(query: String): RelationalContact? =
        ContactMatcher.find(query, memory.relationalContacts.value)

    /** Apariciones registradas de un contacto (libreta, FifoAmigos, charla o llamada). */
    fun appearances(contactId: String): List<ContactAppearance> =
        database()?.contactAppearances?.getAppearances(userId(), contactId) ?: emptyList()

    /** Todas las apariciones agrupadas por contacto, sin una consulta por tarjeta. */
    fun appearancesByContact(): Map<String, List<ContactAppearance>> =
        database()?.contactAppearances?.getAllAppearances(userId())
            ?.groupBy { it.contactId }
            ?: emptyMap()

    /**
     * Teléfonos mencionados que esperan confirmación, agrupados por contacto.
     * Un teléfono ya conocido invalida los indicios pendientes de ese contacto.
     */
    fun pendingPhoneHintsByContact(): Map<String, List<ContactPhoneHint>> {
        val contactsWithPhone = memory.relationalContacts.value
            .filter { !it.phoneNumber.isNullOrBlank() }
            .map { it.id }
            .toSet()
        return database()?.contactPhoneHints?.getAllPending(userId())
            ?.filterNot { it.contactId in contactsWithPhone }
            ?.groupBy { it.contactId }
            ?: emptyMap()
    }

    /** Agrega o actualiza un contacto y limpia sus indicios si ya tiene teléfono. */
    fun addOrUpdate(contact: RelationalContact) {
        val currentUser = userId()
        memory.addOrUpdateRelationalContact(currentUser, contact)
        if (!contact.phoneNumber.isNullOrBlank()) {
            database()?.contactPhoneHints?.deleteForContact(currentUser, contact.id)
        }
        scope.launch {
            remote.postContact(currentUser, contact)
        }
    }

    /**
     * Confirma un teléfono mencionado: lo copia al contacto, lo persiste y lo
     * sincroniza. Devuelve `true` si el indicio existía y se aplicó.
     */
    fun confirmPhoneHint(hintId: String): Boolean {
        val currentUser = userId()
        val hint = database()?.contactPhoneHints?.getPendingById(currentUser, hintId) ?: return false
        val contact = memory.relationalContacts.value.firstOrNull { it.id == hint.contactId } ?: return false

        val updated = contact.copy(phoneNumber = hint.phoneNumber)
        memory.addOrUpdateRelationalContact(currentUser, updated)
        database()?.contactPhoneHints?.deleteForContact(currentUser, contact.id)

        scope.launch {
            remote.postContact(currentUser, updated)
        }
        return true
    }

    /** Descarta un teléfono mencionado sin tocar el contacto ni sincronizarlo. */
    fun discardPhoneHint(hintId: String) {
        database()?.contactPhoneHints?.markDiscarded(userId(), hintId)
    }

    /**
     * Ingesta los contactos ya leídos de una fuente externa (la libreta).
     * La política de fusión y de procedencia vive en [RelationalGraphCoordinator].
     */
    fun ingestContacts(contacts: List<RelationalContact>): Int {
        if (contacts.isEmpty()) return 0

        val merge = coordinator.mergePhoneContacts(
            current = memory.relationalContacts.value,
            phoneContacts = contacts,
            occurredAt = nowIso()
        )
        if (merge.changed.isEmpty() && merge.appearances.isEmpty()) {
            return contacts.size
        }

        val currentUser = userId()
        memory.setDirectRelationalContacts(
            merge.contacts.sortedByDescending { it.closenessScore }
        )
        database()?.relationalContacts?.syncDeviceContactsBatch(currentUser, merge.changed)
        database()?.contactAppearances?.recordAll(currentUser, merge.appearances)
        merge.changed
            .filter { !it.phoneNumber.isNullOrBlank() }
            .forEach { database()?.contactPhoneHints?.deleteForContact(currentUser, it.id) }

        scope.launch {
            remote.syncContacts(currentUser, merge.changed)
        }
        return contacts.size
    }

    /**
     * Ingesta perfiles de FifoAmigos. Una recomendación no es un contacto
     * conocido: entra con rol "Amigo de FifoAmigos" y sin inventar parentesco.
     */
    fun ingestFifoFriends(friends: List<FriendRecommendation>): Int {
        if (friends.isEmpty()) return 0

        val merge = coordinator.mergeFifoFriends(
            current = memory.relationalContacts.value,
            friends = friends,
            occurredAt = nowIso()
        )
        if (merge.changed.isEmpty() && merge.appearances.isEmpty()) {
            return friends.size
        }

        val currentUser = userId()
        memory.setDirectRelationalContacts(
            merge.contacts.sortedByDescending { it.closenessScore }
        )
        database()?.relationalContacts?.syncDeviceContactsBatch(currentUser, merge.changed)
        database()?.contactAppearances?.recordAll(currentUser, merge.appearances)

        scope.launch {
            remote.syncContacts(currentUser, merge.changed)
        }
        return friends.size
    }

    /**
     * Procesa las personas mencionadas en una conversación y las vincula al
     * grafo con su procedencia. Los teléfonos mencionados quedan como indicios.
     */
    fun ingestSessionMentions(
        mentions: List<MentionedPersonExtraction>,
        conversationId: String? = null
    ) {
        if (mentions.isEmpty()) return

        val merge = coordinator.mergeSessionMentions(
            current = memory.relationalContacts.value,
            mentions = mentions,
            conversationId = conversationId,
            occurredAt = nowIso()
        )
        if (merge.changed.isEmpty() && merge.appearances.isEmpty() && merge.phoneHints.isEmpty()) return

        val currentUser = userId()
        memory.setDirectRelationalContacts(
            merge.contacts.sortedByDescending { it.closenessScore }
        )

        merge.changed.forEach { contact ->
            database()?.relationalContacts?.addOrUpdateRelationalContact(currentUser, contact)
            scope.launch {
                remote.postContact(currentUser, contact)
            }
        }
        database()?.contactAppearances?.recordAll(currentUser, merge.appearances)
        database()?.contactPhoneHints?.recordAll(currentUser, merge.phoneHints)
    }

    private companion object {
        fun utcNowIso(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
    }
}
