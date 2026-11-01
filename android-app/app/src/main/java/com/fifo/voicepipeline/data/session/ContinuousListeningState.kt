package com.fifo.voicepipeline.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Proyección de la escucha continua para la UI (ADR-0049).
 *
 * **No es el dueño.** El dueño es `TurnOrchestrator`: él decide cuándo Fifo
 * escucha de forma continua. Este objeto solo guarda el último valor publicado
 * para que la UI y el facade legacy lo observen, y encamina las peticiones de
 * la UI hacia el dueño real con [handler].
 *
 * Sin orquestador (camino `LEGACY` o pipeline detenido) `handler` es `null` y
 * [request] solo actualiza la proyección: no hay un segundo booleano de estado
 * que pueda contradecir al dueño.
 */
class ContinuousListeningState {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Puente al dueño. Lo instala `VoicePipelineManager` solo en `SESSION`. */
    @Volatile
    var handler: ((Boolean) -> Unit)? = null

    /** La UI pide un cambio; el dueño decide y vuelve a publicar por [mirror]. */
    fun request(active: Boolean) {
        _active.value = active
        handler?.invoke(active)
    }

    /** El dueño publica su estado. Única vía legítima de escritura desde arriba. */
    fun mirror(active: Boolean) {
        _active.value = active
    }
}
