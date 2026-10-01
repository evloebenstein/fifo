package com.fifo.voicepipeline.ui.theme

import androidx.compose.ui.graphics.Color

// ── Paleta de Colores Oficial de "Tu Amigo FIFO" (Extraída de Figma) ──
object FifoColors {
    // Modo Oscuro (Pantallas de Bienvenida e Inicio de Sesión)
    val DarkBackground = Color(0xFF141A2D)
    val DarkSurface = Color(0xFF1E263D)
    val DarkInputBg = Color(0xFF1B2238)
    val DarkInputBorder = Color(0xFF2C3754)
    val DarkButtonPrimary = Color(0xFFC7E4F9) // Botón celeste claro
    val DarkButtonText = Color(0xFF10172A)
    val DarkButtonSecondary = Color(0xFF1B233A)
    val DarkBadgeBg = Color(0xFF1F2942)
    val DarkBadgeText = Color(0xFF94A3B8)

    // Modo Claro (Pantallas de Conocerte, Principal, Amigos, Actividades, Perfil)
    val LightBackground = Color(0xFFF6F8FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightCardBorder = Color(0xFFE8EEF5)
    val LightTextPrimary = Color(0xFF151D30)
    val LightTextSecondary = Color(0xFF64748B)
    val LightTextMuted = Color(0xFF94A3B8)

    // Acentos y Tarjetas Fifo
    val HeroBlueCard = Color(0xFFDCEBFA)       // Tarjeta "¿Qué tienes en mente?"
    val HeroBlueBorder = Color(0xFFC7E0F8)
    val NavyPrimary = Color(0xFF141A2D)        // Botón "Hablar con Fifo" y botones oscuros
    val NavyPrimaryHover = Color(0xFF222B45)
    val BlueSoftPill = Color(0xFFE3F0FC)       // Pills y tags activos
    val BlueSoftText = Color(0xFF1E40AF)
    val BlueAccent = Color(0xFF38BDF8)         // Rostro de Fifo y brillo
    val BlueAccentDark = Color(0xFF0284C7)

    // Estados del Pipeline de Voz
    val StatusIdle = Color(0xFF64748B)
    val StatusListening = Color(0xFF10B981)    // Verde esmeralda vivo
    val StatusProcessing = Color(0xFFF59E0B)   // Ámbar
    val StatusSpeaking = Color(0xFF3B82F6)     // Azul
    val StatusError = Color(0xFFEF4444)        // Rojo

    // Compatibilidad
    val Background = DarkBackground
    val Surface = DarkSurface
    val SurfaceLight = DarkInputBg
    val SurfaceHighlight = DarkInputBorder
    val Primary = NavyPrimary
    val Accent = BlueAccent
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = DarkBadgeText
    val Success = StatusListening
    val Warning = StatusProcessing
    val Error = StatusError
    val Listening = StatusListening
    val Processing = StatusProcessing
    val Speaking = StatusSpeaking
    val Disconnected = StatusIdle
}
