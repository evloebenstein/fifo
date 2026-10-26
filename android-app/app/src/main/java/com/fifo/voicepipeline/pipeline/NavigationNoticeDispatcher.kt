package com.fifo.voicepipeline.pipeline

import com.fifo.voicepipeline.audio.TextSanitizer
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.NavigationNotice
import com.fifo.voicepipeline.location.NavigationNoticeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Orquesta la locución de los avisos autónomos de navegación (ADR-0041).
 *
 * Es el módulo profundo detrás de `VoicePipelineManager`:
 *
 * 1. encola el aviso (coalescencia “el último gana”);
 * 2. espera a que el pipeline esté libre, sin plazo;
 * 3. descarta solo los avisos de un destino que ya no está activo;
 * 4. pide la locución al LLM y usa [NavigationNotice.fallbackSpeech] si falla;
 * 5. entrega el texto ya saneado a [speak].
 *
 * No conoce el facade ni Android: recibe la espera, la ruta activa, el narrador
 * y el habla como costuras, así que se puede probar en JVM con `runTest`.
 */
internal class NavigationNoticeDispatcher(
    private val scope: CoroutineScope,
    private val awaitIdle: suspend () -> Unit,
    private val activeDestination: () -> FifoNavigationManager.ActiveDestination?,
    private val narrate: suspend (NavigationNotice) -> String?,
    private val speak: (String) -> Unit,
    private val log: (String) -> Unit = {}
) {

    private val queue = NavigationNoticeQueue()

    /**
     * Encola [notice]. Si no hay un drenaje en curso, arranca uno. El drenaje
     * vive en [scope] y es el único que consume la cola.
     */
    fun dispatch(notice: NavigationNotice) {
        if (!queue.offer(notice)) return

        scope.launch {
            try {
                drain()
            } finally {
                queue.release()
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val first = queue.poll() ?: return
            awaitIdle()

            // Mientras esperaba pudo llegar un hecho más nuevo: gana el último.
            val latest = if (queue.hasPending()) {
                queue.poll() ?: first
            } else {
                first
            }

            if (!isNavigationNoticeRelevant(latest, activeDestination())) {
                log("Aviso ${latest.kind} descartado: el destino activo ya cambió")
                continue
            }

            val narrated = try {
                narrate(latest)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                log("Aviso ${latest.kind}: la narración falló (${failure.message}); se usa el fallback")
                null
            }
            val speech = TextSanitizer.cleanForSpeech(
                narrated?.takeIf { it.isNotBlank() } ?: latest.fallbackSpeech()
            )
            if (speech.isBlank()) continue

            if (narrated == null) {
                log("Aviso ${latest.kind}: LLM no disponible, se usa el fallback estático")
            } else {
                log("Aviso ${latest.kind}: locución redactada por el LLM")
            }
            runCatching { speak(speech) }.onFailure {
                log("No se pudo locutar el aviso ${latest.kind}: ${it.message}")
            }
        }
    }
}

/**
 * Solo se descarta un aviso si la ruta activa ya no es la que lo originó.
 *
 * La llegada se anuncia aunque la ruta se haya cerrado al detectarla; si el
 * usuario ya arrancó otro viaje, la llegada vieja deja de tener sentido.
 */
internal fun isNavigationNoticeRelevant(
    notice: NavigationNotice,
    active: FifoNavigationManager.ActiveDestination?
): Boolean {
    if (notice.kind == NavigationNoticeKind.ARRIVED) {
        return active == null || sameDestination(active, notice)
    }
    return active != null && sameDestination(active, notice)
}

private fun sameDestination(
    active: FifoNavigationManager.ActiveDestination,
    notice: NavigationNotice
): Boolean =
    active.name.equals(notice.destinationName, ignoreCase = true) &&
        active.fullAddress.equals(notice.destinationAddress, ignoreCase = true)
