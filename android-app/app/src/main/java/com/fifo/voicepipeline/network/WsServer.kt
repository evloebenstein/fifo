package com.fifo.voicepipeline.network

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Servidor WebSocket que escucha conexiones del ESP32.
 *
 * Protocolo:
 * - Mensajes binarios: chunks de audio PCM 16-bit, 16kHz, mono
 * - Mensajes de texto: JSON de control (estado, configuración)
 *
 * Solo acepta UNA conexión activa (el ESP32).
 */
class AudioWsServer(
    port: Int,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onClientConnected: () -> Unit,
    private val onClientDisconnected: () -> Unit,
    private val onTextMessage: (String) -> Unit = {},
    private val onError: (Exception) -> Unit = {}
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "AudioWsServer"
    }

    /** Conexión activa del ESP32 (null si no hay) */
    @Volatile
    private var espConnection: WebSocket? = null

    /** ¿Hay un ESP32 conectado? */
    val isClientConnected: Boolean
        get() = espConnection?.isOpen == true

    // ── WebSocketServer callbacks ───────────────────

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i(TAG, "ESP32 conectado desde ${conn.remoteSocketAddress}")

        // Si ya hay una conexión, cerrar la anterior
        espConnection?.let { old ->
            if (old != conn && old.isOpen) {
                Log.w(TAG, "Cerrando conexión anterior")
                old.close()
            }
        }

        espConnection = conn
        onClientConnected()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "ESP32 desconectado: code=$code reason=$reason remote=$remote")
        if (conn == espConnection) {
            espConnection = null
            onClientDisconnected()
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        // ── Audio binario del ESP32 ─────────────────
        val bytes = ByteArray(message.remaining())
        message.get(bytes)
        onAudioReceived(bytes)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // ── Mensaje de control (JSON) ───────────────
        Log.d(TAG, "Texto del ESP32: $message")
        onTextMessage(message)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Error WebSocket: ${ex.message}", ex)
        onError(ex)
    }

    override fun onStart() {
        Log.i(TAG, "Servidor WebSocket iniciado en puerto $port")
        // Timeout de conexión — desconectar clients inactivos
        connectionLostTimeout = 10
    }

    // ── Métodos públicos ────────────────────────────

    /**
     * Envía audio PCM crudo al ESP32 (respuesta de la IA).
     * @param pcmData Bytes PCM 16-bit, 16kHz, mono
     */
    fun sendAudio(pcmData: ByteArray) {
        espConnection?.let { conn ->
            if (conn.isOpen) {
                conn.send(pcmData)
            } else {
                Log.w(TAG, "Intentando enviar audio pero el ESP32 no está conectado")
            }
        }
    }

    /**
     * Envía un mensaje de texto (control/estado) al ESP32.
     */
    fun sendText(message: String) {
        espConnection?.let { conn ->
            if (conn.isOpen) {
                conn.send(message)
            }
        }
    }

    /**
     * Detiene el servidor WebSocket.
     */
    fun stopServer() {
        try {
            stop(1000)
            Log.i(TAG, "Servidor detenido")
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo servidor: ${e.message}")
        }
    }
}
