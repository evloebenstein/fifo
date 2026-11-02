package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.data.model.ContactIdentityResult
import com.fifo.voicepipeline.network.FifoBackendClient

/**
 * Costura de red de la telefonía (ADR-0051).
 *
 * Las skills de llamadas no conocen `FifoBackendClient`: piden identificación y
 * registro de eventos por este puerto. El adaptador de producción absorbe los
 * fallos para que una red caída nunca impida contestar o colgar.
 */
interface CallRemotePort {
    suspend fun identifyContact(
        userId: String,
        callerName: String?,
        phoneNumber: String?
    ): ContactIdentityResult?

    suspend fun logCallEvent(
        userId: String,
        action: String,
        callerName: String,
        phoneNumber: String? = null,
        durationSeconds: Int = 0,
        notes: String? = null
    )
}

/** Adaptador inerte para pruebas y entornos sin red. */
object NoopCallRemote : CallRemotePort {
    override suspend fun identifyContact(
        userId: String,
        callerName: String?,
        phoneNumber: String?
    ): ContactIdentityResult? = null

    override suspend fun logCallEvent(
        userId: String,
        action: String,
        callerName: String,
        phoneNumber: String?,
        durationSeconds: Int,
        notes: String?
    ) = Unit
}

/** Adaptador de producción sobre [FifoBackendClient]. */
object BackendCallRemote : CallRemotePort {
    override suspend fun identifyContact(
        userId: String,
        callerName: String?,
        phoneNumber: String?
    ): ContactIdentityResult? = try {
        FifoBackendClient.identifyContact(userId, callerName, phoneNumber)
    } catch (e: Exception) {
        android.util.Log.d("CallRemote", "Aviso al identificar llamante: ${e.message}")
        null
    }

    override suspend fun logCallEvent(
        userId: String,
        action: String,
        callerName: String,
        phoneNumber: String?,
        durationSeconds: Int,
        notes: String?
    ) {
        try {
            FifoBackendClient.logCallEvent(
                userId = userId,
                action = action,
                callerName = callerName,
                phoneNumber = phoneNumber,
                durationSeconds = durationSeconds,
                notes = notes
            )
        } catch (e: Exception) {
            android.util.Log.d("CallRemote", "Aviso al registrar evento de llamada: ${e.message}")
        }
    }
}
