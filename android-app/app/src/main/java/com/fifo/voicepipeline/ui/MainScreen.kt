package com.fifo.voicepipeline.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fifo.voicepipeline.pipeline.MicSource
import com.fifo.voicepipeline.pipeline.PipelineState
import com.fifo.voicepipeline.ui.components.*
import com.fifo.voicepipeline.ui.screens.*
import com.fifo.voicepipeline.ui.theme.FifoColors

enum class AppFlowScreen {
    WELCOME,
    LOGIN,
    ONBOARDING,
    MAIN_APP
}

/**
 * Pantalla principal y gestor de navegación de "Tu Amigo FIFO".
 * Implementa con fidelidad absoluta el diseño de Figma con:
 * - Pantalla de Bienvenida (Inicio · tu amigo fifo)
 * - Inicio de Sesión (¡Qué bueno verte!)
 * - Registro y Cuéntame sobre ti (Conocerte · tu amigo fifo)
 * - Dashboard Principal con botón central "Hablar con Fifo"
 * - Comunidad y Muro "Fifo amigos" con búsqueda de personas
 * - Actividades e Invitación a amigos
 * - Perfil y Pantalla de Accesibilidad / Ajustes con sincronización BLE
 * - Hoja interactiva de voz en tiempo real con animación de Fifo y selector de micrófono
 */
@Composable
fun MainScreen(
    state: PipelineState,
    micSource: MicSource,
    statusMessage: String,
    transcription: String,
    aiResponse: String,
    rmsLevel: Double,
    isBleConnected: Boolean,
    isBleConnecting: Boolean = false,
    onConnectBle: () -> Unit,
    onTalkFromPhone: () -> Unit = {},
    onClearConversation: () -> Unit,
    isAwake: Boolean = false,
    onWakeUp: () -> Unit = {},
    onSleep: () -> Unit = {},
    currentClaudeKey: String = "",
    onSaveClaudeKey: (String) -> Unit = {},
    onTestClaudeKey: (suspend (String) -> Pair<Boolean, String>)? = null,
    isMicMuted: Boolean = false,
    onToggleMicMute: () -> Unit = {}
) {
    var currentFlowScreen by remember { mutableStateOf(AppFlowScreen.MAIN_APP) }
    var currentTab by remember { mutableStateOf(FifoTab.HOME) }
    val userProfileState by com.fifo.voicepipeline.data.FifoDataRepository.userProfile.collectAsState()
    var userName by remember { mutableStateOf(userProfileState.fullName) }

    LaunchedEffect(userProfileState) {
        userName = userProfileState.fullName
    }

    // Control de ventanas modales interactivas
    var isVoiceSheetOpen by remember { mutableStateOf(false) }
    var isBreathingOpen by remember { mutableStateOf(false) }
    var initialPhoneMicMode by remember { mutableStateOf(false) }

    // Solo abrir el diálogo de Fifo automáticamente si Fifo fue despertado (se dijo "Fifo" o se pulsó hablar)
    LaunchedEffect(state, isAwake) {
        if (isAwake && (state == PipelineState.LISTENING || state == PipelineState.PROCESSING || state == PipelineState.SPEAKING)) {
            isVoiceSheetOpen = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Flujo de Pantallas Principales ───
        when (currentFlowScreen) {
            AppFlowScreen.WELCOME -> {
                WelcomeScreen(
                    onNavigateToOnboarding = { currentFlowScreen = AppFlowScreen.ONBOARDING },
                    onNavigateToLogin = { currentFlowScreen = AppFlowScreen.LOGIN }
                )
            }

            AppFlowScreen.LOGIN -> {
                LoginScreen(
                    onLoginSuccess = { name ->
                        userName = name
                        currentFlowScreen = AppFlowScreen.MAIN_APP
                    },
                    onBackToWelcome = { currentFlowScreen = AppFlowScreen.WELCOME }
                )
            }

            AppFlowScreen.ONBOARDING -> {
                OnboardingScreen(
                    onComplete = { name, _, _ ->
                        userName = name
                        currentFlowScreen = AppFlowScreen.MAIN_APP
                    },
                    onBack = { currentFlowScreen = AppFlowScreen.WELCOME }
                )
            }

            AppFlowScreen.MAIN_APP -> {
                Scaffold(
                    bottomBar = {
                        FifoBottomNav(
                            selectedTab = currentTab,
                            onTabSelected = { currentTab = it }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentTab) {
                            FifoTab.HOME -> {
                                HomeScreen(
                                    userName = userName,
                                    onStartVoiceChat = {
                                        initialPhoneMicMode = false
                                        onWakeUp()
                                        isVoiceSheetOpen = true
                                    },
                                    onTalkFromPhone = {
                                        initialPhoneMicMode = true
                                        onTalkFromPhone()
                                        isVoiceSheetOpen = true
                                    },
                                    onOpenBreathingExercise = { isBreathingOpen = true },
                                    isMicMuted = isMicMuted,
                                    onToggleMicMute = onToggleMicMute,
                                    isBleConnected = isBleConnected,
                                    onConnectBle = {
                                        initialPhoneMicMode = false
                                        onConnectBle()
                                        isVoiceSheetOpen = true
                                    }
                                )
                            }
                            FifoTab.FRIENDS -> {
                                FriendsScreen()
                            }
                            FifoTab.ACTIVITIES -> {
                                ActivitiesScreen(userName = userName)
                            }
                            FifoTab.PROFILE -> {
                                ProfileScreen(
                                    currentName = userName,
                                    onLogout = { currentFlowScreen = AppFlowScreen.WELCOME },
                                    onReopenSetup = { currentFlowScreen = AppFlowScreen.ONBOARDING },
                                    isBleConnected = isBleConnected,
                                    onConnectBle = onConnectBle,
                                    isAwake = isAwake,
                                    onWakeUp = onWakeUp,
                                    onSleep = onSleep,
                                    currentClaudeKey = currentClaudeKey,
                                    onSaveClaudeKey = onSaveClaudeKey,
                                    onTestClaudeKey = onTestClaudeKey,
                                    isMicMuted = isMicMuted,
                                    onToggleMicMute = onToggleMicMute
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Modal de Interacción de Voz en Vivo ("Hablar con Fifo") ───
        if (isVoiceSheetOpen) {
            VoiceInteractionSheet(
                state = state,
                micSource = micSource,
                statusMessage = statusMessage,
                transcription = transcription,
                aiResponse = aiResponse,
                rmsLevel = rmsLevel.toFloat(),
                isBleConnected = isBleConnected,
                isBleConnecting = isBleConnecting,
                initialPhoneMicMode = initialPhoneMicMode,
                onDismiss = { isVoiceSheetOpen = false },
                onConnectBle = onConnectBle,
                onTalkFromPhone = onTalkFromPhone,
                onClearConversation = onClearConversation,
                isAwake = isAwake,
                isMicMuted = isMicMuted,
                onToggleMicMute = onToggleMicMute
            )
        }

        // ── Modal de Respiración Guiada ("Baja el ritmo") ───
        if (isBreathingOpen) {
            BreathingExerciseDialog(
                onDismiss = { isBreathingOpen = false }
            )
        }
    }
}
