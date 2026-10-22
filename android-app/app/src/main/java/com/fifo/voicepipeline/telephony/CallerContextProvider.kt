package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.ui.model.ContactIdentityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Origen de la identidad de un llamante.
 */
enum class CallerContextSource {
    /** No hay nombre fiable: número desconocido, privado o llamada sin identificar. */
    UNKNOWN,

    /** Nombre resuelto por contactos del teléfono o por el grafo relacional local. */
    LOCAL_CONTACT,

    /** El backend confirmó que la persona existe en el grafo/historial. */
    BACKEND_KNOWN,

    /** El backend respondió, pero la persona no está registrada. */
    BACKEND_NEW
}

/**
 * Contexto de un llamante, listo para el anuncio y para el agente.
 *
 * `recentTopics` y `contextualNotes` NO se leen en voz alta durante el timbrado:
 * son material para cuando el usuario pregunte o después de contestar.
 */
data class CallerContext(
    val phase: CallPhase,
    val displayName: String,
    val phoneNumber: String? = null,
    val relationshipRole: String? = null,
    val isKnown: Boolean = false,
    val source: CallerContextSource = CallerContextSource.UNKNOWN,
    val contextualNotes: String? = null,
    val recentTopics: String? = null,
    val suggestedGreeting: String? = null,
    val suggestedFollowup: String? = null
) {
    val isUnknown: Boolean
        get() = !isKnown

    /**
     * Texto de timbrado. Es deliberadamente corto y no incluye temas recientes.
     */
    fun announcementText(userFirstName: String? = null): String {
        val prefix = userFirstName?.trim()?.takeIf { it.isNotBlank() }?.let { "¡$it! " } ?: ""
        val who = when {
            isKnown && !relationshipRole.isNullOrBlank() ->
                "$displayName, su ${relationshipRole.lowercase()}"
            isKnown -> displayName
            displayName.isNotBlank() &&
                    displayName != "un número desconocido" &&
                    !CallerNameClassifier.looksLikePhoneNumber(displayName) ->
                "$displayName (no está en sus contactos)"
            !phoneNumber.isNullOrBlank() -> "un número desconocido ($phoneNumber)"
            CallerNameClassifier.looksLikePhoneNumber(displayName) ->
                "un número desconocido ($displayName)"
            else -> "un número desconocido"
        }
        return "${prefix}Le está llamando $who. Diga 'Fifo contesta' o 'Fifo cuelga'."
    }
}

/**
 * Contacto local resuelto por el teléfono o por el grafo relacional de Fifo.
 */
data class LocalCallerContact(
    val name: String,
    val relationshipRole: String? = null,
    val contextualMemory: String? = null
)

fun interface LocalCallerContactResolver {
    fun resolve(callerName: String?, phoneNumber: String?): LocalCallerContact?
}

/**
 * Fuente de identidad remota. Se inyecta para poder probar el proveedor en JVM.
 */
fun interface CallerIdentitySource {
    suspend fun identify(
        userId: String,
        callerName: String?,
        phoneNumber: String?
    ): ContactIdentityResult?
}

/**
 * Proveedor de contexto de llamante en dos fases.
 *
 * 1. [local] es síncrono: permite anunciar de inmediato el nombre que ya conoce el
 *    teléfono o el grafo local.
 * 2. [enrich] consulta el backend con un timeout corto (~800 ms). Si el backend no
 *    responde, devuelve el contexto local sin bloquear el timbrado.
 *
 * El proveedor no habla, no contesta y no toca telefonía: solo clasifica y enriquece.
 */
class CallerContextProvider(
    private val identitySource: CallerIdentitySource,
    private val localContactResolver: LocalCallerContactResolver = LocalCallerContactResolver { _, _ -> null },
    private val enrichmentTimeoutMs: Long = 800L
) {

    /**
     * Contexto inmediato, sin red.
     */
    fun local(snapshot: CallSnapshot): CallerContext {
        val rawName = snapshot.callerName?.trim().orEmpty()
        val phone = snapshot.phoneNumber?.trim().orEmpty()
        val localContact = localContactResolver.resolve(
            rawName.ifBlank { null },
            phone.ifBlank { null }
        )
        val localName = localContact?.name?.trim().orEmpty()
        val hasLocalContact = localName.isNotBlank()
        val snapshotNameUsable = rawName.isNotBlank() &&
                !CallerNameClassifier.isPlaceholder(rawName) &&
                !CallerNameClassifier.looksLikePhoneNumber(rawName)
        val isKnown = hasLocalContact || snapshotNameUsable
        val fallbackNumber = phone.ifBlank {
            if (CallerNameClassifier.looksLikePhoneNumber(rawName)) rawName else ""
        }
        val displayName = when {
            hasLocalContact -> localName
            snapshotNameUsable -> rawName
            fallbackNumber.isNotBlank() -> fallbackNumber
            else -> "un número desconocido"
        }
        val role = localContact?.relationshipRole
            ?.trim()
            ?.takeIf { CallerNameClassifier.isMeaningfulRelationship(it) }

        return CallerContext(
            phase = snapshot.phase,
            displayName = displayName,
            phoneNumber = fallbackNumber.ifBlank { null },
            relationshipRole = role,
            isKnown = isKnown,
            source = when {
                hasLocalContact || snapshotNameUsable -> CallerContextSource.LOCAL_CONTACT
                else -> CallerContextSource.UNKNOWN
            },
            contextualNotes = localContact?.contextualMemory
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Enriquece el contexto local con el backend. Nunca lanza por timeout o error de red.
     */
    suspend fun enrich(
        snapshot: CallSnapshot,
        userId: String,
        local: CallerContext = local(snapshot)
    ): CallerContext {
        if (!snapshot.hasCall || userId.isBlank()) return local

        val identity = try {
            withTimeoutOrNull(enrichmentTimeoutMs) {
                identitySource.identify(userId, snapshot.callerName, snapshot.phoneNumber)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return local

        return merge(local, identity)
    }

    private fun merge(local: CallerContext, identity: ContactIdentityResult): CallerContext {
        val backendKnown = identity.isKnown
        val isKnown = local.isKnown || backendKnown
        val source = when {
            backendKnown -> CallerContextSource.BACKEND_KNOWN
            local.isKnown -> CallerContextSource.LOCAL_CONTACT
            else -> CallerContextSource.BACKEND_NEW
        }
        val backendName = identity.contactName.trim().takeIf {
            it.isNotBlank() &&
                    !CallerNameClassifier.isPlaceholder(it) &&
                    !CallerNameClassifier.looksLikePhoneNumber(it)
        }
        val displayName = when {
            backendKnown && backendName != null -> backendName
            local.isKnown -> local.displayName
            backendName != null -> backendName
            else -> local.displayName
        }
        val backendRole = identity.relationshipRole
            .trim()
            .takeIf { CallerNameClassifier.isMeaningfulRelationship(it) }
        val relationshipRole = if (isKnown) {
            backendRole ?: local.relationshipRole
        } else {
            null
        }
        val recentTopics = identity.recentTopics.trim().takeIf {
            it.isNotBlank() && !it.equals("Sin charlas previas registradas", ignoreCase = true)
        }

        return local.copy(
            displayName = displayName,
            relationshipRole = relationshipRole,
            isKnown = isKnown,
            source = source,
            contextualNotes = identity.contextualNotes.trim().takeIf { it.isNotBlank() }
                ?: local.contextualNotes,
            recentTopics = recentTopics,
            suggestedGreeting = identity.suggestedGreeting.trim().takeIf { it.isNotBlank() },
            suggestedFollowup = identity.suggestedFollowup.trim().takeIf { it.isNotBlank() }
        )
    }
}

/**
 * Clasificación de nombres de llamante sin depender de Android ni del backend.
 */
object CallerNameClassifier {

    private val placeholders = setOf(
        "llamada desconocida",
        "llamada entrante",
        "llamada en curso",
        "desconocido",
        "desconocida",
        "privado",
        "privada",
        "spam",
        "contacto",
        "la llamada",
        "sin identificar",
        "número desconocido",
        "numero desconocido"
    )

    private val genericRoles = setOf(
        "contacto telefónico",
        "contacto telefonico",
        "contacto catalogado",
        "contacto nuevo catalogado",
        "conocido",
        "sin rol"
    )

    fun isPlaceholder(raw: String?): Boolean {
        val value = raw?.trim()?.lowercase() ?: return true
        if (value.isBlank()) return true
        if (value in placeholders) return true
        return value.startsWith("número ") || value.startsWith("numero ")
    }

    fun looksLikePhoneNumber(raw: String?): Boolean {
        val value = raw?.trim() ?: return false
        if (value.isBlank()) return false
        val digits = value.count { it.isDigit() }
        if (digits < 6) return false
        return value.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
    }

    fun isMeaningfulRelationship(raw: String?): Boolean {
        val value = raw?.trim()?.lowercase() ?: return false
        return value.isNotBlank() && value !in genericRoles
    }
}
