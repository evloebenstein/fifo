package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.ui.model.CommunityActivityItem

/**
 * Política de borrador para “sugerir una actividad con Fifo”.
 *
 * Es una función pura: recibe la idea dictada, los gustos del usuario y la
 * identidad del organizador, y devuelve la actividad propuesta. No conoce
 * `StateFlow`, SQLite ni red; `FifoDataRepository` solo aporta el perfil.
 *
 * Reglas:
 * - la categoría, el título y el lugar por defecto se eligen por la primera
 *   familia de palabras que aparezca (pintura, cocina, caminata, música,
 *   lectura) y si no hay coincidencia se usa un encuentro vecinal;
 * - los gustos detectados en la idea se agregan en orden fijo; si no hay
 *   ninguno, se usan los dos primeros gustos del perfil;
 * - una idea de 8 a 55 caracteres se usa como título; en otro caso se usa el
 *   título por defecto;
 * - una idea de más de 20 caracteres se cita en la descripción.
 */
internal object ActivitySuggestionPolicy {

    private data class Draft(
        val category: String,
        val defaultTitle: String,
        val defaultLocation: String
    )

    private const val MIN_TITLE_LENGTH = 8
    private const val MAX_TITLE_LENGTH = 55
    private const val MIN_DESCRIPTION_LENGTH = 20
    private const val MAX_PROFILE_TASTES = 2

    fun draft(
        ideaPrompt: String,
        userTastes: List<String>,
        organizerId: String,
        organizerName: String
    ): CommunityActivityItem {
        val promptClean = ideaPrompt.trim()
        val lower = promptClean.lowercase()
        val suggestion = categoryFor(lower)

        val tastesFound = mutableListOf<String>()
        if (lower.contains("pint") || lower.contains("arte")) tastesFound.add("Pintura y arte")
        if (lower.contains("cocin") || lower.contains("recet")) tastesFound.add("Cocina tradicional")
        if (lower.contains("camin") || lower.contains("parque")) tastesFound.add("Paseos al aire libre")
        if (lower.contains("music") || lower.contains("bolero")) tastesFound.add("Música clásica")
        if (lower.contains("libr") || lower.contains("histori")) tastesFound.add("Historias de vida")
        if (tastesFound.isEmpty() && userTastes.isNotEmpty()) {
            tastesFound.addAll(userTastes.take(MAX_PROFILE_TASTES))
        }

        val generatedTitle = if (promptClean.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH) {
            promptClean.replaceFirstChar { it.uppercase() }
        } else {
            suggestion.defaultTitle
        }

        val generatedDescription = if (promptClean.length > MIN_DESCRIPTION_LENGTH) {
            "Fifo organizó esta propuesta: $promptClean. Una oportunidad perfecta para compartir con vecinos que aprecian los mismos pasatiempos."
        } else {
            "Reunión comunitaria guiada por Fifo para disfrutar de una grata conversación y realizar actividades amenas en conjunto."
        }

        return CommunityActivityItem(
            id = "draft_suggested",
            title = generatedTitle,
            description = generatedDescription,
            spotsLabel = "Quedan 7 cupos",
            spotsMax = 8,
            spotsTaken = 1,
            dateLabel = "Próximo sábado · 16:00",
            locationName = suggestion.defaultLocation,
            distanceLabel = "A 500 m",
            iconCategory = suggestion.category,
            organizerId = organizerId,
            organizerName = organizerName.ifBlank { "Vecino" },
            organizerType = "user",
            targetTastes = tastesFound,
            isJoined = true
        )
    }

    private fun categoryFor(lowerPrompt: String): Draft = when {
        lowerPrompt.contains("pint") || lowerPrompt.contains("arte") ||
            lowerPrompt.contains("dibuj") || lowerPrompt.contains("acuarel") ->
            Draft("palette", "Encuentro de dibujo y pintura libre", "Centro Cultural o Plaza de las Esculturas")

        lowerPrompt.contains("cocin") || lowerPrompt.contains("recet") ||
            lowerPrompt.contains("pan") || lowerPrompt.contains("comid") ||
            lowerPrompt.contains("dulc") ->
            Draft("coffee", "Compartir de cocina y recetas caseras", "Café vecinal o rincón del té")

        lowerPrompt.contains("camin") || lowerPrompt.contains("parque") ||
            lowerPrompt.contains("plant") || lowerPrompt.contains("flor") ||
            lowerPrompt.contains("jard") ->
            Draft("nature", "Caminata y paseo por las áreas verdes", "Acceso principal del Parque Central")

        lowerPrompt.contains("music") || lowerPrompt.contains("cant") ||
            lowerPrompt.contains("guitarr") || lowerPrompt.contains("bolero") ->
            Draft("music", "Tarde musical y canciones del recuerdo", "Sala de música del centro comunitario")

        lowerPrompt.contains("libr") || lowerPrompt.contains("le") ||
            lowerPrompt.contains("cuento") || lowerPrompt.contains("histori") ->
            Draft("book", "Club de lectura y tertulia de historias", "Biblioteca vecinal")

        else ->
            Draft("nature", "Encuentro vecinal para compartir y conversar", "Café o parque cercano")
    }
}
