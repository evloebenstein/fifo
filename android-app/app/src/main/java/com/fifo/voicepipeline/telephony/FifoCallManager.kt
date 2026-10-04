package com.fifo.voicepipeline.telephony

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.ui.model.IncomingCallInfo

/**
 * Gestor de telefonía de Fifo tipo Alexa:
 * 1. Detecta llamadas entrantes en segundo plano.
 * 2. Consulta la libreta de contactos para identificar quién llama (ej: 'Hija Carmen', 'Dr. Pérez').
 * 3. Notifica a Fifo para que le anuncie en voz alta al usuario quién le llama.
 * 4. Permite contestar en altavoz o colgar la llamada mediante comandos de voz ("Fifo contesta", "Fifo cuelga").
 */
class FifoCallManager(
    private val context: Context,
    private val onAnnounceCall: (callerName: String, phoneNumber: String) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "FifoCallManager"
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    init {
        // Enlazar acciones en el repositorio central
        FifoDataRepository.onAnswerCallAction = { answerCall() }
        FifoDataRepository.onHangupCallAction = { hangupCall() }
    }

    /**
     * Inicia la escucha de eventos telefónicos.
     */
    fun startListening() {
        val hasPhonePermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhonePermission) {
            Log.w(TAG, "Permiso READ_PHONE_STATE no concedido aún.")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager?.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
                Log.i(TAG, "Registrado TelephonyCallback (Android 12+)")
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
                Log.i(TAG, "Registrado PhoneStateListener legacy")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escucha de telefonía: ${e.message}")
        }
    }

    /**
     * Procesa los cambios de estado de la llamada telefónica.
     */
    fun handleCallState(state: Int, incomingNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val number = incomingNumber ?: ""
                val callerName = resolveContactName(number)
                Log.i(TAG, "Llamada entrante sonando: '$callerName' ($number)")

                val callInfo = IncomingCallInfo(
                    callerName = callerName,
                    phoneNumber = number,
                    isRinging = true
                )
                FifoDataRepository.setIncomingCall(callInfo)
                onAnnounceCall(callerName, number)
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Llamada en curso (descolgado)")
                val current = FifoDataRepository.incomingCall.value
                if (current != null) {
                    FifoDataRepository.setIncomingCall(current.copy(isRinging = false))
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(TAG, "Teléfono en reposo (llamada finalizada o colgada)")
                FifoDataRepository.setIncomingCall(null)
            }
        }
    }

    /**
     * Resuelve el nombre del contacto a partir del número de teléfono.
     */
    @SuppressLint("Range")
    fun resolveContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Llamada entrante"

        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            // Verificar si coincide con el contacto de emergencia del perfil
            val profile = FifoDataRepository.userProfile.value
            if (phoneNumber.contains(profile.emergencyContactPhone.takeLast(8))) {
                return profile.emergencyContactName
            }
            return if (phoneNumber.isNotBlank()) phoneNumber else "Llamada entrante"
        }

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else {
                    val profile = FifoDataRepository.userProfile.value
                    if (phoneNumber.contains(profile.emergencyContactPhone.takeLast(8))) {
                        profile.emergencyContactName
                    } else {
                        phoneNumber
                    }
                }
            } ?: phoneNumber
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando contactos: ${e.message}")
            phoneNumber
        }
    }

    /**
     * Contesta la llamada entrante y activa automáticamente el altavoz del celular.
     */
    @SuppressLint("MissingPermission")
    fun answerCall(): Boolean {
        Log.i(TAG, "Intentando contestar llamada entrante...")

        val hasAnswerPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAnswerPermission) {
                telecomManager?.acceptRingingCall()
            } else {
                // En versiones previas o fallback
                Log.w(TAG, "No se cuenta con permiso ANSWER_PHONE_CALLS directo.")
            }

            // Activar altavoz del teléfono para que el usuario no tenga que pegar el celular a la oreja
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = true
            Log.i(TAG, "Altavoz del celular activado para llamada")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error contestando llamada: ${e.message}")
            false
        }
    }

    /**
     * Cuelga, rechaza o detiene la llamada en curso.
     */
    @SuppressLint("MissingPermission")
    fun hangupCall(): Boolean {
        Log.i(TAG, "Intentando colgar / rechazar llamada...")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val hasAnswerPermission = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED

                if (hasAnswerPermission) {
                    val ended = telecomManager?.endCall() ?: false
                    Log.i(TAG, "Llamada finalizada vía TelecomManager: $ended")
                    true
                } else {
                    Log.w(TAG, "Falta permiso ANSWER_PHONE_CALLS para endCall.")
                    false
                }
            } else {
                Log.w(TAG, "endCall directo requiere Android 9+.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error colgando llamada: ${e.message}")
            false
        }
    }

    /**
     * Libera los listeners de telefonía.
     */
    fun stopListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
                telephonyManager?.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
                telephonyCallback = null
            } else if (phoneStateListener != null) {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
                phoneStateListener = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo escucha de telefonía: ${e.message}")
        }
    }
}
