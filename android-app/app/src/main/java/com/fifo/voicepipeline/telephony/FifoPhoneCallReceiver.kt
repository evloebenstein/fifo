package com.fifo.voicepipeline.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.ui.model.IncomingCallInfo

/**
 * BroadcastReceiver para detectar llamadas entrantes mediante intent de Android.
 */
class FifoPhoneCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FifoPhoneCallReceiver"
        var onIncomingCallDetected: ((callerName: String, phoneNumber: String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

            Log.i(TAG, "Phone state broadcast: $stateStr (número: $incomingNumber)")

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    val callManager = FifoCallManager(context)
                    val callerName = callManager.resolveContactName(incomingNumber)

                    val callInfo = IncomingCallInfo(
                        callerName = callerName,
                        phoneNumber = incomingNumber,
                        isRinging = true
                    )
                    FifoDataRepository.setIncomingCall(callInfo)
                    onIncomingCallDetected?.invoke(callerName, incomingNumber)
                }

                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    val current = FifoDataRepository.incomingCall.value
                    if (current != null) {
                        FifoDataRepository.setIncomingCall(current.copy(isRinging = false))
                    }
                }

                TelephonyManager.EXTRA_STATE_IDLE -> {
                    FifoDataRepository.setIncomingCall(null)
                }
            }
        }
    }
}
