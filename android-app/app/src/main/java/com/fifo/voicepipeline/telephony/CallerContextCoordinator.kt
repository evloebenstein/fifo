package com.fifo.voicepipeline.telephony

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordina [TelephonyPort] con [CallerContextProvider] sin meter telefonía dentro
 * de `VoiceSession`.
 *
 * Comportamiento:
 * 1. Al cambiar el estado de llamada emite **inmediatamente** el contexto local.
 * 2. Lanza el enriquecimiento backend en paralelo (timeout del proveedor, ~800 ms).
 * 3. Si la llamada termina o cambia antes de que llegue el backend, descarta el
 *    resultado viejo y limpia el contexto.
 *
 * No inicia ni detiene la telefonía: el dueño del ciclo de vida es el facade.
 */
class CallerContextCoordinator(
    private val telephony: TelephonyPort,
    private val provider: CallerContextProvider,
    private val scope: CoroutineScope,
    private val userIdProvider: () -> String
) {
    private val _context = MutableStateFlow<CallerContext?>(null)
    val context: StateFlow<CallerContext?> = _context.asStateFlow()

    private var collectorJob: Job? = null
    private var enrichmentJob: Job? = null

    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            telephony.callState.collect { snapshot ->
                enrichmentJob?.cancel()
                enrichmentJob = null

                if (!snapshot.hasCall) {
                    _context.value = null
                    return@collect
                }

                val local = provider.local(snapshot)
                _context.value = local

                val userId = userIdProvider()
                if (userId.isBlank()) return@collect

                enrichmentJob = launch {
                    val enriched = provider.enrich(snapshot, userId, local)
                    // Si la llamada cambió mientras esperábamos, no pisar el contexto nuevo.
                    if (telephony.callState.value == snapshot) {
                        _context.value = enriched
                    }
                }
            }
        }
    }

    fun stop() {
        enrichmentJob?.cancel()
        enrichmentJob = null
        collectorJob?.cancel()
        collectorJob = null
        _context.value = null
    }
}
