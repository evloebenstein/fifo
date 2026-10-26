package com.fifo.voicepipeline.pipeline.session

import android.content.Context
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.data.local.SqliteConversationJournal
import com.fifo.voicepipeline.data.memory.RepositorySessionMemoryConsolidator
import com.fifo.voicepipeline.network.CloudApiClient
import com.fifo.voicepipeline.pipeline.MicSource
import com.fifo.voicepipeline.skills.FifoSkillRegistry
import com.fifo.voicepipeline.telephony.AndroidTelephonyPort
import com.fifo.voicepipeline.telephony.BackendCallerIdentitySource
import com.fifo.voicepipeline.telephony.CallPhase
import com.fifo.voicepipeline.telephony.CallerContextCoordinator
import com.fifo.voicepipeline.telephony.CallerContextProvider
import com.fifo.voicepipeline.telephony.LocalCallerContact
import com.fifo.voicepipeline.telephony.LocalCallerContactResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Composición de producción del camino nuevo.
 *
 * Es el único lugar que construye `VoiceSession`, el adaptador de captura, el
 * `AndroidTelephonyPort` y el coordinador de contexto de llamante. El facade legacy
 * no construye nada de esto cuando el flag está apagado, y este pipeline no construye
 * el camino legacy cuando está encendido.
 */
internal class VoiceSessionPipeline(
    context: Context,
    private val scope: CoroutineScope,
    private val cloudClient: CloudApiClient,
    private val skillRegistry: FifoSkillRegistry,
    initialMicMuted: Boolean
) {
    private companion object {
        const val TAG = "VoiceSessionPipeline"
    }

    val telephony = AndroidTelephonyPort(context, scope)

    private val callerContextProvider = CallerContextProvider(
        identitySource = BackendCallerIdentitySource(),
        localContactResolver = LocalCallerContactResolver { name, phone ->
            resolveLocalContact(name, phone)
        }
    )
    private val callerContext = CallerContextCoordinator(
        telephony = telephony,
        provider = callerContextProvider,
        scope = scope,
        userIdProvider = { FifoDataRepository.userProfile.value.id }
    )

    private val outbound = AndroidAudioHardwareAdapter(context, scope)
    private val inbound = AndroidAudioHardwareInboundAdapter(context)

    private val memory = MemoryCoordinator(
        journal = SqliteConversationJournal(context.applicationContext),
        consolidator = RepositorySessionMemoryConsolidator(cloudClient),
        scope = scope,
        userIdProvider = { FifoDataRepository.userProfile.value.id }
    )

    /**
     * Detector acústico de invocación (ADR-0043).
     *
     * Hoy es [NoopWakeWordDetector]: el léxico sigue siendo el único que despierta
     * a Fifo. El modelo entrenado (openWakeWord) se enchufa exactamente aquí, y
     * `VoiceSession` le entrega las tramas que ya captura `CaptureArbiter`: el
     * detector nunca abre micrófono, no habla y no autoriza acciones.
     */
    private val wakeWordDetector: WakeWordDetector = NoopWakeWordDetector

    val session = VoiceSession(
        scope = scope,
        outboundAdapter = outbound,
        speechCognition = cloudClient,
        skillRegistry = skillRegistry,
        callState = telephony.callState,
        onCallAction = { action -> handleCallAction(action) },
        initialMicMuted = initialMicMuted,
        memory = memory,
        wakeWordDetector = wakeWordDetector
    )

    val state: StateFlow<com.fifo.voicepipeline.pipeline.PipelineState> = session.state
    val isAwake: StateFlow<Boolean> = session.isAwake
    val transcription: StateFlow<String> = session.transcription
    val aiResponse: StateFlow<String> = session.aiResponse
    val statusMessage: StateFlow<String> = session.statusMessage
    val rmsLevel: StateFlow<Double> = session.rmsLevel
    val isMicMuted: StateFlow<Boolean> = session.isMicMuted

    private var started = false
    private var callStateJob: Job? = null
    private var callerContextJob: Job? = null
    private var announcedRinging = false
    private var lastCallPhase = CallPhase.NONE

    fun start() {
        if (started) return
        started = true

        telephony.start()
        callerContext.start()
        inbound.setMicMuted(session.isMicMuted.value)
        inbound.setCallMode(telephony.callState.value.hasCall)
        memory.start()
        restoreRecentConversation()
        session.resetSession()
        session.sleep()
        observeCallState()
        observeCallerContext()
        inbound.startCapture { frame ->
            session.onAudioFrameReceived(frame)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        callStateJob?.cancel()
        callStateJob = null
        callerContextJob?.cancel()
        callerContextJob = null
        callerContext.stop()
        telephony.stop()
        inbound.stopCapture()
        session.resetSession()
        memory.stop()
        outbound.shutdown()
        announcedRinging = false
        lastCallPhase = CallPhase.NONE
    }

    fun setBleConnected(connected: Boolean) {
        inbound.setBleConnected(connected)
    }

    fun onBleFrame(pcmData: ByteArray) {
        inbound.handleIncomingBleFrame(pcmData)
    }

    fun setMicSource(source: MicSource) {
        inbound.setMicSource(source)
    }

    fun talkFromPhone() {
        inbound.setMicSource(MicSource.PHONE)
        session.setMicMuted(false)
        session.dispatchIntent(VoiceSessionIntent.GoToSleep)
    }

    fun setMicMuted(muted: Boolean) {
        inbound.setMicMuted(muted)
        session.setMicMuted(muted)
    }

    fun wakeUpManually() {
        session.dispatchIntent(VoiceSessionIntent.WakeUpManually)
    }

    fun goToSleep() {
        session.dispatchIntent(VoiceSessionIntent.GoToSleep)
    }

    fun processTextQuery(query: String) {
        session.dispatchIntent(VoiceSessionIntent.ProcessTextQuery(query))
    }

    fun clearConversation() {
        cloudClient.clearHistory()
        session.resetSession()
    }

    /**
     * El historial en RAM de CloudApiClient se pierde al reiniciar. El diario
     * durable es la fuente de continuidad: restauramos los últimos 4 turnos
     * (8 mensajes) antes de aceptar la primera consulta de la nueva sesión.
     */
    private fun restoreRecentConversation() {
        cloudClient.clearHistory()
        val recent = try {
            memory.recentTurns(4)
        } catch (_: Exception) {
            emptyList()
        }
        recent.asReversed().forEach { turn ->
            cloudClient.recordTurn(turn.userText, turn.repairText ?: turn.assistantText)
        }
    }

    private fun observeCallState() {
        callStateJob = scope.launch {
            telephony.callState.collect { snapshot ->
                inbound.setCallMode(snapshot.hasCall)
                if (snapshot.phase == lastCallPhase) return@collect

                when (snapshot.phase) {
                    CallPhase.RINGING -> {
                        // El coordinador de contexto puede haber empezado el anuncio
                        // antes que este observador. No lo cortemos con IncomingCall.
                        if (!announcedRinging) {
                            session.dispatchIntent(VoiceSessionIntent.IncomingCall)
                        }
                        session.setCallStatus("Llamada entrante · Diga 'Fifo contesta' o 'Fifo cuelga'")
                    }
                    CallPhase.ACTIVE -> {
                        if (lastCallPhase == CallPhase.NONE) {
                            // Fifo arrancó con una llamada ya en curso: despertar sin
                            // reproducir el anuncio de timbrado.
                            session.dispatchIntent(VoiceSessionIntent.IncomingCall)
                        }
                        session.setCallStatus("Llamada activa · Diga 'Fifo cuelga'")
                    }
                    CallPhase.NONE -> {
                        if (lastCallPhase != CallPhase.NONE) {
                            session.dispatchIntent(VoiceSessionIntent.CallEnded)
                        }
                        announcedRinging = false
                    }
                }
                lastCallPhase = snapshot.phase
            }
        }
    }

    private fun observeCallerContext() {
        callerContextJob = scope.launch {
            callerContext.context.collect { context ->
                if (context == null) {
                    announcedRinging = false
                    return@collect
                }
                if (context.phase != CallPhase.RINGING || announcedRinging) return@collect

                announcedRinging = true
                val firstName = FifoDataRepository.userProfile.value.fullName
                    .split(" ")
                    .firstOrNull { it.isNotBlank() }
                session.speakResponse(context.announcementText(firstName))
            }
        }
    }

    private fun handleCallAction(action: CallAction): Boolean {
        val snapshot = telephony.callState.value
        if (!snapshot.hasCall) return false

        return when (action) {
            CallAction.ANSWER -> {
                if (!snapshot.isRinging) return false
                val handled = telephony.answer()
                if (handled) session.setCallStatus("Llamada contestada")
                handled
            }

            CallAction.HANGUP -> {
                val handled = telephony.hangup()
                if (handled) session.setCallStatus("Llamada finalizada")
                handled
            }

            CallAction.CALLER_STATUS -> {
                val firstName = FifoDataRepository.userProfile.value.fullName
                    .split(" ")
                    .firstOrNull { it.isNotBlank() }
                val announcement = callerContext.context.value?.announcementText(firstName)
                    ?: "No tengo información de esta llamada."
                session.speakResponse(announcement)
                true
            }
        }
    }

    private fun resolveLocalContact(name: String?, phone: String?): LocalCallerContact? {
        val byName = name?.trim()?.takeIf { it.isNotBlank() }?.let {
            FifoDataRepository.findContactByRoleOrName(it)
        }
        val contact = byName ?: phone?.trim()?.takeIf { it.isNotBlank() }?.let { number ->
            FifoDataRepository.relationalContacts.value.firstOrNull { it.phoneNumber == number }
        }
        return contact?.let {
            LocalCallerContact(
                name = it.contactName,
                relationshipRole = it.relationshipRole,
                contextualMemory = it.contextualMemory
            )
        }
    }
}
