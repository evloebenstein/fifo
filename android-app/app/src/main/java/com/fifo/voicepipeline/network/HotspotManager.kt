package com.fifo.voicepipeline.network

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Gestor y detector del punto de acceso Wi-Fi (Hotspot móvil).
 *
 * Permite:
 * 1. Detectar si la zona Wi-Fi del celular está activa o apagada.
 * 2. Obtener la IP local exacta asignada a la interfaz del hotspot (ej: 192.168.43.1).
 * 3. Abrir la pantalla de ajustes de Zona Wi-Fi con un solo toque desde la app.
 */
object HotspotManager {
    private const val TAG = "HotspotManager"

    /**
     * Verifica si la Zona Wi-Fi (Tethering AP) del celular está encendida.
     * Utiliza reflexión sobre WifiManager y verificación de interfaces de red activas.
     */
    fun isHotspotActive(context: Context): Boolean {
        // Método 1: Reflexión en WifiManager (isWifiApEnabled)
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                val isApEnabled = method.invoke(wifiManager) as? Boolean
                if (isApEnabled == true) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "isWifiApEnabled no accesible por reflexión: ${e.message}")
        }

        // Método 2: Inspección de interfaces de red típicas de AP (ap0, swlan0, wlan1, etc.)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()

                // Nombres comunes para la interfaz de punto de acceso en Android
                val isApInterface = name.startsWith("ap") ||
                        name.startsWith("swlan") ||
                        name.startsWith("softap") ||
                        name.startsWith("wlan1") ||
                        name.startsWith("rndis")

                if (isApInterface && iface.isUp) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error inspeccionando interfaces de red: ${e.message}")
        }

        return false
    }

    /**
     * Obtiene la dirección IP asignada a la interfaz del Hotspot.
     * Retorna "192.168.43.1" (estándar de Android) si no se puede determinar.
     */
    fun getHotspotIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.43.1"
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()

                val isApInterface = name.startsWith("ap") ||
                        name.startsWith("swlan") ||
                        name.startsWith("softap") ||
                        name.startsWith("wlan1")

                if (iface.isUp) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: ""
                            if (isApInterface || ip.startsWith("192.168.43.") || ip.startsWith("192.168.44.")) {
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error obteniendo IP del hotspot: ${e.message}")
        }

        return "192.168.43.1"
    }

    /**
     * Abre directamente la pantalla de configuración de "Zona Wi-Fi / Conexión compartida"
     * para que el usuario active el Hotspot en 1 solo toque.
     */
    fun openHotspotSettings(context: Context) {
        val tetherIntent = Intent().apply {
            action = "android.settings.TETHER_SETTINGS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(tetherIntent)
        } catch (e1: Exception) {
            Log.w(TAG, "ACTION_TETHER_SETTINGS falló, intentando WIRELESS_SETTINGS: ${e1.message}")
            try {
                val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(wirelessIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "WIRELESS_SETTINGS falló, abriendo ajustes generales: ${e2.message}")
                val generalIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(generalIntent)
            }
        }
    }
}
