package com.fifo.voicepipeline.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.fifo.voicepipeline.data.AppGraph
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
            val graph = AppGraph.instanceOrNull()
            if (graph == null) {
                Log.w(TAG, "AppGraph no instalado; se ignora el evento de llamada")
                return
            }
            val callState = graph.telephony
            val profile = graph.identity.userProfile.value
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

            Log.i(TAG, "Phone state broadcast: $stateStr (número: $incomingNumber)")

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    val callerName = FifoCallManager.resolveContactName(
                        context,
                        incomingNumber,
                        profile
                    )

                    val callInfo = IncomingCallInfo(
                        callerName = callerName,
                        phoneNumber = incomingNumber,
                        isRinging = true
                    )
                    callState.setIncomingCall(callInfo)
                    onIncomingCallDetected?.invoke(callerName, incomingNumber)
                }

                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    val current = callState.incomingCall.value
                    if (current != null) {
                        callState.setIncomingCall(current.copy(isRinging = false))
                    }
                }

                TelephonyManager.EXTRA_STATE_IDLE -> {
                    callState.setIncomingCall(null)
                }
            }
        }
    }
}
