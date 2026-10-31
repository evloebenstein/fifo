package com.fifo.voicepipeline.data.context

import com.fifo.voicepipeline.data.model.CurrentLocationInfo
import com.fifo.voicepipeline.data.model.DeepContextResult
import com.fifo.voicepipeline.data.modules.EpisodicMemoryModule
import com.fifo.voicepipeline.data.modules.UserIdentityModule
import com.fifo.voicepipeline.pipeline.session.ConversationContextProvider
import java.util.Calendar
import java.util.Locale

/**
 * Dueño único del contexto conversacional (ADR-0049).
 *
 * Compone en una sola ventana:
 * 1. hora/fecha real;
 * 2. ubicación GPS o domicilio estimado;
 * 3. contexto de la ruta activa;
 * 4. memoria episódica compacta (perfil, gustos, contactos, recordatorios, última charla);
 * 5. la política de recuperación de memoria como guía del prompt.
 *
 * La guía es **texto de sistema**, no una locución: el LLM sigue redactando la
 * respuesta. Los cinco disparadores viven aquí, en un solo lugar.
 *
 * `searchDeep` es la capa 2: el LLM la invoca con `recall_past_context`; nunca
 * se llama antes de hablar ni bloquea el turno.
 */
class ConversationContext(
    private val identity: UserIdentityModule,
    private val memory: EpisodicMemoryModule,
    private val deepContext: DeepContextPort,
    private val location: () -> CurrentLocationInfo? = { null },
    private val navigation: () -> String = { "" },
    private val clock: () -> Calendar = { Calendar.getInstance() }
) : ConversationContextProvider {

    override fun buildContextWindow(): String = buildString {
        append(buildTemporalContext()).append("\n")

        val current = identity.userProfile.value
        val gps = location()
        if (gps != null && gps.isGpsActive) {
            append("UBICACIÓN GPS ACTUAL: ${gps.address} (${gps.city}). ")
            append("Coordenadas: ${gps.latitude}, ${gps.longitude}. Busca y guía siempre desde ${gps.city}.\n")
        } else if (current.preferredAddress.isNotBlank()) {
            append("UBICACIÓN ESTIMADA: ${current.preferredAddress} (${current.city}).\n")
        }

        val navigationContext = navigation()
        if (navigationContext.isNotBlank()) {
            append(navigationContext).append("\n")
        }

        val compactMemory = memory.buildCompactContextWindow(current, maxFragments = 1)
        if (compactMemory.isNotBlank()) {
            append(compactMemory).append("\n")
        }

        append(MEMORY_RECOVERY_GUIDE)
    }.trim()

    /**
     * Capa 2: backend primero, índice local como respaldo.
     *
     * Solo se invoca cuando el LLM pide detalle con `recall_past_context`. El
     * borrado remoto aún no existe en el backend; el filtro local se documenta
     * como hueco conocido en ADR-0049.
     */
    suspend fun searchDeep(query: String): DeepContextResult {
        val userId = identity.userProfile.value.id
        val remote = runCatching { deepContext.search(userId, query) }.getOrNull()
        if (remote != null && remote.relevantExcerpts.isNotEmpty()) {
            return remote
        }
        return memory.searchDeepLocal(query)
    }

    private fun buildTemporalContext(): String {
        val now = clock()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val timeFormatted = String.format(Locale.US, "%02d:%02d", hour, minute)
        val dayOfWeek = when (now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Lunes"
            Calendar.TUESDAY -> "Martes"
            Calendar.WEDNESDAY -> "Miércoles"
            Calendar.THURSDAY -> "Jueves"
            Calendar.FRIDAY -> "Viernes"
            Calendar.SATURDAY -> "Sábado"
            else -> "Domingo"
        }
        val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
        val month = when (now.get(Calendar.MONTH)) {
            0 -> "Enero"; 1 -> "Febrero"; 2 -> "Marzo"; 3 -> "Abril"; 4 -> "Mayo"; 5 -> "Junio"
            6 -> "Julio"; 7 -> "Agosto"; 8 -> "Septiembre"; 9 -> "Octubre"; 10 -> "Noviembre"; else -> "Diciembre"
        }
        val year = now.get(Calendar.YEAR)
        val (period, greeting) = when (hour) {
            in 6..11 -> "Mañana" to "Buenos días"
            in 12..19 -> "Tarde" to "Buenas tardes"
            else -> "Noche" to "Buenas noches"
        }
        return "HORA ACTUAL: $timeFormatted hrs ($period, saludo: '$greeting'). " +
            "Hoy es $dayOfWeek, $dayOfMonth de $month de $year."
    }

    private companion object {
        /**
         * Política de recuperación (ADR-0049). Es una guía del system prompt, no
         * una frase enlatada: describe cuándo buscar y qué hacer si no hay
         * evidencia. `recall_past_context` apunta aquí en una sola línea para
         * no duplicar la lista.
         */
        val MEMORY_RECOVERY_GUIDE = """
            === POLÍTICA DE RECUPERACIÓN DE MEMORIA ===
            Busca antes de afirmar. Invoca 'recall_past_context' cuando:
            1. La pregunta quede abierta o inconclusa y el contexto no traiga evidencia.
            2. Falte un detalle que el usuario espera que recuerdes (nombre, fecha, lugar, dosis, receta).
            3. Estés por afirmar, insinuar o sugerir algo sobre el pasado.
            4. El usuario corrija o dude de algo que dijiste.
            5. Haya una laguna o pida precisión sobre una charla anterior.
            Si la búsqueda no devuelve evidencia, dilo con honestidad; nunca inventes.
        """.trimIndent()
    }
}
