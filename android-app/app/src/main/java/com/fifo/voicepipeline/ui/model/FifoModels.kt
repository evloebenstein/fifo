package com.fifo.voicepipeline.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Modelo de una conversación pasada con Fifo
 */
data class PastConversationItem(
    val id: String,
    val title: String,
    val date: String,
    val duration: String,
    val summary: String,
    val tag: String,
    val iconName: String = "chat"
) {
    val icon: ImageVector
        get() = when (iconName) {
            "restaurant" -> Icons.Outlined.Restaurant
            "music" -> Icons.Outlined.MusicNote
            "heart" -> Icons.Outlined.FavoriteBorder
            "air" -> Icons.Outlined.Air
            else -> Icons.Outlined.ChatBubbleOutline
        }
}

/**
 * Modelo de un recuerdo que Fifo aprendió de las conversaciones
 */
data class FifoMemoryItem(
    val id: String,
    val emoji: String,
    val title: String,
    val detail: String,
    val learnedDate: String
)

/**
 * Publicación creada por la usuaria en su perfil / Fifo Amigos
 */
data class UserSocialPost(
    val id: String,
    val author: String = "Lucía",
    val age: Int = 68,
    val category: String,
    val timeAgo: String,
    val content: String,
    var likes: Int,
    val commentsCount: Int,
    val accentColorHex: Long = 0xFF38BDF8
)

/**
 * Artículo o historia de blog sobre un gusto del usuario generado por Fifo
 * a partir de las charlas cotidianas (sin información sensible)
 */
data class FifoTasteStory(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val tags: List<String>,
    val iconCategory: String,
    val learnedFrom: String
)

/**
 * Datos del perfil del usuario gestionados por Fifo y sincronizados con la Base de Datos
 */
data class UserProfileData(
    val id: String = "usr_01",
    val fullName: String = "Lucía González",
    val birthDate: String = "14 de Mayo, 1958",
    val birthYear: Int = 1958,
    val estimatedAge: Int = 68,
    val genderIdentity: String = "Mujer",
    val city: String = "Santiago, Chile",
    val bioAi: String = "Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café. Disfruta compartir con Fifo anécdotas de su familia, preparar recetas caseras y cuidar las orquídeas de su jardín.",
    val emergencyContactName: String = "Carmen (Hija)",
    val emergencyContactPhone: String = "+56987654321",
    val preferredAddress: String = "Av. Providencia 1234, Providencia, Santiago"
)

/**
 * Recordatorio creado por voz por Fifo (para pastillas, compromisos, familia)
 */
data class FifoReminderItem(
    val id: String,
    val title: String,
    val timeStr: String,
    val category: String = "medication", // "medication", "family", "hobby", "health"
    val isCompleted: Boolean = false,
    val createdAt: String = "Hoy"
)

// ═════════════════════════════════════════════════════════════════════
//  MEMORIA DE DOBLE CAPA: Fragmentos Livianos (on-device) vs.
//  Transcripción Completa (server-side)
// ═════════════════════════════════════════════════════════════════════

/**
 * Fragmento compacto de una conversación pasada, almacenado LOCALMENTE en el celular.
 *
 * Diseño de doble capa:
 * - En el SERVIDOR (Firestore / Supabase) se guarda la conversación COMPLETA
 *   con cada turno de voz transcrito, todos los detalles y el contexto íntegro.
 * - En el CELULAR solo se guardan estos fragmentos livianos:
 *   temas clave, entidades mencionadas y un resumen compacto de ≤150 palabras.
 *
 * Beneficios:
 * 1. Latencia mínima: Fifo tiene contexto inmediato sin descargar conversaciones enteras.
 * 2. Si necesita más detalle → usa `recall_past_context` skill para buscar en el server.
 * 3. Privacidad: los datos sensibles completos solo viven en el servidor cifrado,
 *    no en caché local.
 */
data class ConversationFragment(
    val id: String,
    /** ID de la conversación completa almacenada en el servidor */
    val serverConversationId: String,
    /** Temas clave extraídos (ej: "orquídeas", "cumpleaños de Tomás", "pastillas presión") */
    val keyTopics: List<String>,
    /** Entidades nombradas (personas, lugares, objetos relevantes) */
    val namedEntities: List<String>,
    /** Estado anímico detectado en la conversación ("contenta", "pensativa", "preocupada") */
    val detectedMood: String = "neutral",
    /** Resumen compacto de la charla (≤150 palabras, suficiente para dar contexto) */
    val compactSummary: String,
    /** Etiqueta temática principal */
    val primaryTag: String,
    /** Duración de la conversación original en segundos */
    val durationSeconds: Int = 0,
    /** Timestamp ISO de la conversación */
    val recordedAt: String = "",
    /** Si algún fragmento contenía información sensible de salud (confirmada con consentimiento) */
    val containsSensitiveHealth: Boolean = false
)

/**
 * Resultado de una búsqueda de contexto profundo en el servidor.
 * Cuando Fifo necesita más detalle sobre un tema de nicho o un dato pasado,
 * usa el skill `recall_past_context` que retorna esto.
 */
data class DeepContextResult(
    /** Fragmentos del servidor que coincidieron con la consulta */
    val relevantExcerpts: List<String>,
    /** Entidades encontradas en las conversaciones buscadas */
    val foundEntities: List<String>,
    /** Resumen sintetizado del contexto profundo recuperado */
    val synthesizedContext: String,
    /** Cuántas conversaciones completas se analizaron en el servidor */
    val conversationsSearched: Int = 0
)

// ═════════════════════════════════════════════════════════════════════
//  LOCALIZADOR DEL DISPOSITIVO FIFO (Rastreo ESP32 + GPS Celular)
// ═════════════════════════════════════════════════════════════════════

/**
 * Estado de ubicación y rastreo del robot físico Fifo.
 *
 * Dado que el ESP32 no tiene módulo GPS integrado propio:
 * 1. El celular guarda las coordenadas GPS del celular cada vez que
 *    el robot se conecta o desconecta por Bluetooth.
 * 2. Mide la intensidad de señal BLE (RSSI) para estimar proximidad en la casa.
 * 3. Permite activar un beeper/alarma sonora en el parlante del ESP32
 *    y encender su pantalla OLED con "¡AQUÍ ESTOY!" para encontrarlo al instante.
 */
data class FifoDeviceLocation(
    val isConnected: Boolean = false,
    val lastConnectedTime: String = "Hoy a las 18:30",
    val lastKnownLatitude: Double = -33.4255,
    val lastKnownLongitude: Double = -70.6143,
    val lastKnownAddress: String = "Av. Providencia 1234, Providencia, Santiago",
    val lastKnownRoom: String = "Cerca del Living / Mesa de noche",
    val signalStrengthRssi: Int = -64,
    val isBeeping: Boolean = false
)

/**
 * Información de ubicación geográfica actual obtenida del GPS del celular.
 */
data class CurrentLocationInfo(
    val latitude: Double = -33.4255,
    val longitude: Double = -70.6143,
    val address: String = "Av. Providencia 1234",
    val city: String = "Providencia, Santiago",
    val isGpsActive: Boolean = true
)

// ═════════════════════════════════════════════════════════════════════
//  GESTIÓN DE LLAMADAS Y CONTROL DEL TELÉFONO TIPO ALEXA
// ═════════════════════════════════════════════════════════════════════

/**
 * Estado reactivo de llamadas entrantes detectadas por el celular.
 */
data class IncomingCallInfo(
    val callerName: String = "Llamada desconocida",
    val phoneNumber: String = "",
    val isRinging: Boolean = false,
    val callTimestamp: Long = System.currentTimeMillis()
)

/**
 * Estado de control de hardware del celular (Linterna, Batería, Volumen).
 */
data class PhoneHardwareStatus(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val volumePercent: Int = 80
)
