package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.network.FifoBackendClient
import com.fifo.voicepipeline.ui.model.ContactIdentityResult

/**
 * Adaptador de producción contra `FifoBackendClient`.
 *
 * Vive separado del proveedor para que el núcleo de contexto de llamante se pueda
 * probar en JVM sin cargar la red ni Android.
 */
class BackendCallerIdentitySource : CallerIdentitySource {
    override suspend fun identify(
        userId: String,
        callerName: String?,
        phoneNumber: String?
    ): ContactIdentityResult? = FifoBackendClient.identifyContact(userId, callerName, phoneNumber)
}
