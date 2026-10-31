package com.fifo.voicepipeline.data.context

import com.fifo.voicepipeline.data.model.DeepContextResult
import com.fifo.voicepipeline.network.FifoBackendClient

/**
 * Costura de búsqueda profunda (GraphRAG) del backend.
 *
 * El detalle completo de una conversación solo vive en el servidor; la ventana
 * local guarda el resumen. Esta costura permite que
 * [ConversationContext.searchDeep] consulte el servidor primero y caiga al
 * índice local si no hay red, sin meter HTTP dentro del módulo de memoria.
 */
interface DeepContextPort {
    /** Devuelve `null` si el backend no respondió o no hay contexto remoto. */
    suspend fun search(userId: String, query: String): DeepContextResult?
}

/** Producción: consulta `/users/{id}/recall-context`. */
class BackendDeepContextPort : DeepContextPort {
    override suspend fun search(userId: String, query: String): DeepContextResult? =
        FifoBackendClient.queryDeepContextFromServer(userId = userId, query = query)
}

/**
 * Doble determinista para pruebas: devuelve una respuesta fija o `null`.
 */
class InMemoryDeepContextPort(
    private var result: DeepContextResult? = null
) : DeepContextPort {
    var calls: MutableList<Pair<String, String>> = mutableListOf()

    fun respondWith(value: DeepContextResult?) {
        result = value
    }

    override suspend fun search(userId: String, query: String): DeepContextResult? {
        calls.add(userId to query)
        return result
    }
}
