package com.fifo.voicepipeline.data.contacts

import com.fifo.voicepipeline.data.model.RelationalContact
import java.text.Normalizer

/**
 * Resuelve una persona del grafo relacional a partir de un nombre, rol, apodo o
 * fragmento dicho por el usuario.
 *
 * Es una política pura: no lee ni escribe SQLite, no observa `StateFlow` y no
 * conoce la UI. El repositorio le entrega la lista actual y persiste el
 * resultado cuando corresponda.
 *
 * Reglas de coincidencia:
 *
 * 1. Se ignoran mayúsculas y acentos.
 * 2. Primero se busca una coincidencia exacta de nombre, rol o apodo, en el
 *    orden actual del grafo (que llega ordenado por cercanía).
 * 3. Si no hay exacta, se acepta una coincidencia parcial o por tokens,
 *    priorizando mayor `closenessScore`.
 * 4. Una consulta vacía nunca resuelve un contacto: evita que una skill llame
 *    o escriba a la primera persona del grafo por accidente.
 */
object ContactMatcher {

    private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private const val MIN_TOKEN_LENGTH = 3

    /**
     * Devuelve el contacto que mejor representa [query], o `null` si no hay uno.
     */
    fun find(query: String, contacts: List<RelationalContact>): RelationalContact? {
        val normalizedQuery = normalize(query.trim())
        if (normalizedQuery.isBlank()) return null

        val candidates = contacts.map { contact -> contact to keysOf(contact) }

        // 1. Coincidencia exacta de nombre, rol o apodo, conservando el orden actual.
        candidates.firstOrNull { (_, keys) -> keys.matchesExactly(normalizedQuery) }
            ?.let { return it.first }

        // 2. Coincidencia parcial o por tokens, priorizando cercanía emocional.
        val queryTokens = tokensOf(normalizedQuery)
        return candidates
            .sortedByDescending { (contact, _) -> contact.closenessScore }
            .firstOrNull { (_, keys) -> keys.matchesPartially(normalizedQuery, queryTokens) }
            ?.first
    }

    private data class ContactKeys(
        val name: String,
        val role: String,
        val alias: String
    ) {
        fun matchesExactly(query: String): Boolean =
            query == name || query == role || query == alias

        fun matchesPartially(query: String, queryTokens: List<String>): Boolean {
            if (name.contains(query) || query.contains(name)) return true
            if (role.contains(query) || query.contains(role)) return true
            if (alias.isNotBlank() && (alias.contains(query) || query.contains(alias))) return true

            val contactTokens = tokensOf(name, role, alias)
            return queryTokens.any { queryToken ->
                contactTokens.any { contactToken ->
                    contactToken.contains(queryToken) || queryToken.contains(contactToken)
                }
            }
        }
    }

    private fun keysOf(contact: RelationalContact): ContactKeys = ContactKeys(
        name = normalize(contact.contactName),
        role = normalize(contact.relationshipRole),
        alias = contact.alias?.let(::normalize) ?: ""
    )

    private fun tokensOf(vararg texts: String): List<String> =
        texts
            .flatMap { it.split(" ", "-", "_", ".") }
            .filter { it.length >= MIN_TOKEN_LENGTH }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
}
