package com.fifo.voicepipeline.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fifo.voicepipeline.MainActivity

/**
 * Servicio en primer plano (Foreground Service) que mantiene a FIFO
 * ejecutándose continuamente en segundo plano (24/7).
 *
 * Mantiene la conexión Bluetooth con el ESP32-S3 y la escucha
 * activa para despertar cuando el usuario dice "FIFO".
 */
class FifoVoiceService : Service() {

    companion object {
        private const val TAG = "FifoVoiceService"
        private const val CHANNEL_ID = "fifo_continuous_voice"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FifoVoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FifoVoiceService::class.java)
            context.stopService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Iniciando FifoVoiceService para ejecución continua en segundo plano")

        com.fifo.voicepipeline.data.FifoDataRepository.initialize(applicationContext)
        createNotificationChannel()
        try {
            val notification = buildNotification("FIFO está activo · Di 'Fifo' para hablar")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando startForeground: ${e.message}", e)
        }

        // WakeLock parcial para evitar que la CPU se duerma al apagar la pantalla
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fifo:continuous_mic_wakelock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 horas máx
        } catch (e: Exception) {
            Log.e(TAG, "Error adquiriendo WakeLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY asegura que si el sistema mata el servicio por memoria, lo reviva automáticamente
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Deteniendo FifoVoiceService")
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando WakeLock: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fifo Asistente Continuo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene a Fifo escuchando y conectado al ESP32 por Bluetooth"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tu amigo Fifo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
