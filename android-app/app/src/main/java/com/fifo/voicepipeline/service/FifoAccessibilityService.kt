package com.fifo.voicepipeline.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servicio de Accesibilidad de Fifo.
 *
 * Cumple dos propósitos fundamentales:
 * 1. Concede prioridad de nivel de sistema en Android AudioPolicy (hasAccessibilityPermission),
 *    lo que garantiza que la captura de audio para Fifo NO sea silenciada durante llamadas
 *    o videollamadas concurrentes (WeChat, WhatsApp, Meet, llamadas celulares).
 * 2. Permite controlar llamadas (contestar, colgar, aceptar o rechazar) de forma automatizada
 *    e inclusiva mediante la voz ("Fifo contesta", "Fifo cuelga").
 */
class FifoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FifoAccessibility"

        @Volatile
        var instance: FifoAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun isEnabled(context: android.content.Context): Boolean {
            if (isRunning) return true
            return try {
                val expected = "${context.packageName}/${FifoAccessibilityService::class.java.name}"
                val expectedShort = "${context.packageName}/.service.FifoAccessibilityService"
                val enabledServices = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                enabledServices.split(":").any {
                    it.equals(expected, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true)
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "FifoAccessibilityService conectado con éxito. Captura concurrente de llamadas y control habilitados.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Eventos de interfaz (cambios de ventana, etc.)
    }

    override fun onInterrupt() {
        Log.w(TAG, "FifoAccessibilityService interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "FifoAccessibilityService destruido")
    }

    /**
     * Intenta hacer clic en el botón de colgar o rechazar llamada en la aplicación activa
     * (soporta WeChat, WhatsApp, Google Meet, Dialers estándar, etc.).
     */
    fun clickHangup(): Boolean {
        Log.i(TAG, "Buscando botón de colgar/finalizar llamada en pantalla...")
        val hangupKeywords = listOf(
            "colgar", "finalizar", "rechazar", "cortar", "terminar",
            "hang up", "end call", "decline", "reject", "end",
            "挂断", "结束"
        )
        val hangupIdSubstrings = listOf(
            "hangup", "hang_up", "end_call", "endcall", "decline", "reject", "btn_hangup", "call_end"
        )

        return searchAndClick(hangupKeywords, hangupIdSubstrings)
    }

    /**
     * Intenta hacer clic en el botón de contestar o aceptar llamada en la aplicación activa.
     */
    fun clickAnswer(): Boolean {
        Log.i(TAG, "Buscando botón de contestar/aceptar llamada en pantalla...")
        val answerKeywords = listOf(
            "contestar", "aceptar", "responder", "atender",
            "answer", "accept",
            "接听", "接受"
        )
        val answerIdSubstrings = listOf(
            "answer", "accept", "btn_answer", "call_answer", "pickup"
        )

        return searchAndClick(answerKeywords, answerIdSubstrings)
    }

    private fun searchAndClick(keywords: List<String>, idSubstrings: List<String>): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (window in windows) {
                    window.root?.let { roots.add(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error obteniendo ventanas secundarias: ${e.message}")
            }
        }

        for (root in roots) {
            val targetNode = findActionNode(root, keywords, idSubstrings)
            if (targetNode != null) {
                val clicked = performClickAction(targetNode)
                Log.i(TAG, "Acción de clic realizada en nodo: text='${targetNode.text}', desc='${targetNode.contentDescription}', éxito=$clicked")
                return clicked
            }
        }

        Log.w(TAG, "No se encontró ningún botón coincidente en pantalla")
        return false
    }

    private fun findActionNode(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        idSubstrings: List<String>
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase()?.trim().orEmpty()

        val textMatch = keywords.any { kw ->
            text.contains(kw) || desc.contains(kw)
        }
        val idMatch = idSubstrings.any { idPart ->
            viewId.contains(idPart)
        }

        if (textMatch || idMatch) {
            // Verificar si es clickable directamente o sus ancestros
            var clickableNode: AccessibilityNodeInfo? = node
            while (clickableNode != null && !clickableNode.isClickable) {
                clickableNode = clickableNode.parent
            }
            if (clickableNode != null && clickableNode.isClickable) {
                return clickableNode
            }
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findActionNode(child, keywords, idSubstrings)
            if (found != null) return found
        }

        return null
    }

    private fun performClickAction(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
