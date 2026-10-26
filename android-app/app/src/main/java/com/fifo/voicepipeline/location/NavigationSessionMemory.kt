package com.fifo.voicepipeline.location

/**
 * Estado conversacional de una sesión de navegación.
 *
 * Mientras Fifo acompaña al usuario puede pasar todo tipo de cosas entre medio:
 * la persona se detiene a comprar algo, pide más tiempo, conversa con un vecino,
 * corrige la ruta o explica que primero debe hacer un trámite. Nada de eso debe
 * perderse ni convertir el guiado en una locución fija: el LLM necesita ese
 * contexto para decidir si reindica el camino, si espera o si retoma el resumen.
 *
 * Esta clase es puro estado de dominio: no habla, no conoce Android y no genera
 * frases. [describeForPrompt] entrega hechos estructurados para el prompt y el
 * LLM decide cómo decirlos.
 *
 * [snapshot] y [restore] son la costura de persistencia (ADR-0041): el dueño de
 * la ruta guarda la foto en disco y la repone al volver a arrancar.
 */
class NavigationSessionMemory(
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class PlannedStop(
        val label: String,
        val addedAtMillis: Long
    )

    data class Pause(
        val reason: String,
        val untilMillis: Long?,
        val startedAtMillis: Long
    )

    /**
     * Foto inmutable del estado para persistirlo o restaurarlo (ADR-0041).
     * No incluye la identidad de la ruta: eso vive en [FifoNavigationManager].
     */
    data class Snapshot(
        val stops: List<PlannedStop>,
        val pause: Pause?,
        val lastCorrection: String?,
        val lastCorrectionAtMillis: Long
    )

    private val stops = mutableListOf<PlannedStop>()

    /**
     * Se invoca tras cada mutación efectiva para que el dueño de la ruta
     * persista la foto. No se dispara durante [restore] ni cuando no cambia nada.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private var suppressNotify = false

    @Volatile
    var pause: Pause? = null
        private set

    @Volatile
    var lastCorrection: String? = null
        private set

    private var lastCorrectionAtMillis = 0L

    // ── Encargos en el camino ─────────────────────────────────────────────

    /**
     * Registra una parada o encargo mencionado por el usuario ("comprar pan").
     * Devuelve `null` si el texto está vacío o ya estaba anotado.
     */
    @Synchronized
    fun addStop(label: String): PlannedStop? {
        val clean = label.trim()
        if (clean.isBlank()) return null
        if (stops.any { it.label.equals(clean, ignoreCase = true) }) return null
        val stop = PlannedStop(clean, clock())
        stops += stop
        notifyChanged()
        return stop
    }

    @Synchronized
    fun pendingStops(): List<PlannedStop> = stops.toList()

    /** Marca todos los encargos como realizados y los devuelve. */
    @Synchronized
    fun completeStops(): List<PlannedStop> {
        val done = stops.toList()
        stops.clear()
        if (done.isNotEmpty()) notifyChanged()
        return done
    }

    // ── Pausas ────────────────────────────────────────────────────────────

    @Synchronized
    fun pause(reason: String?, minutes: Int?): Pause {
        val now = clock()
        val until = minutes?.takeIf { it > 0 }?.let { now + it * 60_000L }
        val created = Pause(
            reason = reason?.trim().orEmpty(),
            untilMillis = until,
            startedAtMillis = now
        )
        pause = created
        notifyChanged()
        return created
    }

    @Synchronized
    fun resume(): Pause? {
        val previous = pause
        pause = null
        if (previous != null) notifyChanged()
        return previous
    }

    /**
     * `true` si hay una pausa vigente. Una pausa con plazo se expira sola.
     */
    @Synchronized
    fun isPaused(): Boolean {
        val current = pause ?: return false
        val until = current.untilMillis
        if (until != null && clock() >= until) {
            pause = null
            notifyChanged()
            return false
        }
        return true
    }

    /** Minutos restantes de una pausa con plazo; `null` si es indefinida o no hay pausa. */
    @Synchronized
    fun remainingPauseMinutes(): Int? {
        if (!isPaused()) return null
        val until = pause?.untilMillis ?: return null
        val remaining = (until - clock()).coerceAtLeast(1L)
        return ((remaining + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    // ── Correcciones de ruta ──────────────────────────────────────────────

    @Synchronized
    fun recordCorrection(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        lastCorrection = clean
        lastCorrectionAtMillis = clock()
        notifyChanged()
    }

    @Synchronized
    fun clearCorrection() {
        if (lastCorrection == null && lastCorrectionAtMillis == 0L) return
        lastCorrection = null
        lastCorrectionAtMillis = 0L
        notifyChanged()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Synchronized
    fun reset() {
        stops.clear()
        pause = null
        lastCorrection = null
        lastCorrectionAtMillis = 0L
        notifyChanged()
    }

    /** Foto consistente del estado para persistir (ADR-0041). */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        stops = stops.toList(),
        pause = pause,
        lastCorrection = lastCorrection,
        lastCorrectionAtMillis = lastCorrectionAtMillis
    )

    /**
     * Reemplaza el estado con una foto guardada. Una pausa con plazo ya vencido
     * se restaura sin pausa: el usuario ya no está esperando.
     */
    @Synchronized
    fun restore(snapshot: Snapshot) {
        suppressNotify = true
        try {
            stops.clear()
            stops += snapshot.stops
            val restoredPause = snapshot.pause
            val until = restoredPause?.untilMillis
            pause = if (until != null && clock() >= until) null else restoredPause
            lastCorrection = snapshot.lastCorrection
            lastCorrectionAtMillis = snapshot.lastCorrectionAtMillis
        } finally {
            suppressNotify = false
        }
    }

    private fun notifyChanged() {
        if (suppressNotify) return
        onChanged?.invoke()
    }

    fun hasContext(): Boolean = pendingStops().isNotEmpty() || isPaused() || lastCorrection != null

    /**
     * Hechos de la sesión para inyectar en el system prompt. Devuelve cadena
     * vacía cuando no hay nada que contar.
     */
    fun describeForPrompt(): String {
        if (!hasContext()) return ""

        return buildString {
            appendLine("=== SESIÓN DE NAVEGACIÓN CONVERSACIONAL ===")

            val pending = pendingStops()
            if (pending.isNotEmpty()) {
                append("- ENCARGOS/PARADAS PENDIENTES EN EL CAMINO: ")
                append(
                    pending.joinToString("; ") { stop ->
                        "${stop.label} (anotado hace ${ageMinutes(stop.addedAtMillis)} min)"
                    }
                )
                appendLine(".")
            }

            if (isPaused()) {
                val current = pause
                val reason = current?.reason.orEmpty().ifBlank { "sin motivo indicado" }
                val limit = remainingPauseMinutes()
                    ?.let { "quedan $it min" }
                    ?: "sin límite de tiempo"
                appendLine("- PAUSA ACTIVA DEL USUARIO: $reason ($limit). No lo apure ni lo interrumpa con avisos de desvío.")
            }

            lastCorrection?.let { correction ->
                val age = ((clock() - lastCorrectionAtMillis) / 60_000L).toInt()
                appendLine("- ÚLTIMA CORRECCIÓN DEL USUARIO SOBRE LA RUTA (hace $age min): \"$correction\"")
            }

            appendLine(
                "- REGLA DE SESIÓN: use la herramienta 'manage_navigation_session' para anotar " +
                    "encargos en el camino (add_stop), pausar o reanudar el acompañamiento " +
                    "(pause/resume) y registrar correcciones (correct). Cuando el usuario diga " +
                    "que está listo, reanude y retome la guía resumiendo el destino y los " +
                    "encargos pendientes. No invente calles ni distancias."
            )
        }.trim()
    }

    private fun ageMinutes(millis: Long): Int =
        ((clock() - millis).coerceAtLeast(0L) / 60_000L).toInt()
}
