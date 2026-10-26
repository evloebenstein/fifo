package com.fifo.voicepipeline.pipeline

import com.fifo.voicepipeline.location.NavigationNotice

/**
 * Cola de coalescencia de avisos autónomos de navegación (ADR-0041).
 *
 * El motor puede detectar varios hechos mientras Fifo está ocupada hablando o
 * pensando. No se descarta ninguno por duración: el más reciente reemplaza a
 * los anteriores y se locuta cuando el turno termina. Así el usuario nunca oye
 * un aviso viejo de una cuadra que ya pasó, y el acompañamiento no se pierde
 * porque la respuesta en curso tarde más de unos segundos.
 *
 * [offer] devuelve `true` solo para el primer aviso de una tanda: ese llamador
 * es el dueño del drenaje y debe llamar a [poll] hasta que devuelva `null`.
 * Un `null` libera la tanda, de modo que el siguiente [offer] arranque de nuevo.
 */
internal class NavigationNoticeQueue {

    private val lock = Any()
    private var pending: NavigationNotice? = null
    private var draining = false

    /**
     * Encola [notice] (el último gana). Devuelve `true` si hay que arrancar el
     * drenaje; `false` si ya hay uno en curso y basta con reemplazar el pendiente.
     */
    fun offer(notice: NavigationNotice): Boolean = synchronized(lock) {
        pending = notice
        if (draining) {
            false
        } else {
            draining = true
            true
        }
    }

    /** Toma el aviso más reciente. `null` indica que no queda nada y libera la tanda. */
    fun poll(): NavigationNotice? = synchronized(lock) {
        val next = pending
        pending = null
        if (next == null) draining = false
        next
    }

    /** `true` si llegó un aviso nuevo que puede reemplazar al que se iba a locutar. */
    fun hasPending(): Boolean = synchronized(lock) { pending != null }

    /** Libera la tanda sin tocar el pendiente (cierre anormal del drenaje). */
    fun release() = synchronized(lock) { draining = false }
}
