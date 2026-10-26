package com.fifo.voicepipeline.location

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fifo.voicepipeline.ui.model.CurrentLocationInfo
import java.util.Locale

/**
 * Motor de navegación activa y acompañamiento autónomo en tiempo real (Estilo Copiloto Waze Inteligente).
 *
 * Características clave:
 * 1. Monitoreo GPS continuo autónomo en segundo plano (no espera a que el usuario pregunte).
 * 2. Avisos proactivos de desvío, quietud prolongada, proximidad (100 m, 50 m) y llegada,
 *    entregados como [NavigationNotice] estructurados. El motor NO redacta frases: el
 *    pipeline pide al LLM que las componga con el contexto y la conversación en curso.
 * 3. Memoria conversacional de la sesión ([NavigationSessionMemory]): encargos en el
 *    camino, pausas pactadas y correcciones del usuario. Durante una pausa consciente
 *    se suspenden los avisos para no interrumpir.
 * 4. Memoria inmutable de la ruta: Fifo NUNCA olvida a dónde vas ni confunde la dirección.
 * 5. Conciencia espacial y ambiental: entiende comuna, calle, entorno
 *    interior/exterior, brújula del cuerpo y referencias cercanas.
 * 6. Persistencia de la ruta activa (ADR-0041): destino, encargos, pausa y
 *    corrección sobreviven al cierre de la app y se restauran al arrancar.
 *
 * ## GeoIntentSeam (ADR-0007)
 * This class no longer holds a reference to `android.content.Context`. All
 * geo-hardware access is done through the injected [locationAdapter].
 * GPS listener registration still requires a [LocationManager] (Android SDK);
 * that dependency is resolved lazily via the optional [locationManagerProvider].
 * In JVM unit tests, pass `locationManagerProvider = { null }` to disable
 * real GPS updates and drive state via [simulateLocationTick].
 *
 * No hay singleton: cada `FifoSkillRegistry` construye una instancia y la
 * inyecta en sus consumidores (ADR-0038).
 */
class FifoNavigationManager(
    private val locationAdapter: LocationAdapter,
    /**
     * Factory that returns the Android [LocationManager], or `null` in test
     * environments where no real GPS hardware is available.
     */
    private val locationManagerProvider: () -> LocationManager? = { null },
    /**
     * Persistencia de la ruta activa y de su sesión conversacional (ADR-0041).
     * `null` deja el manager puramente volátil, como en los dobles de prueba.
     */
    private val sessionStore: NavigationSessionStore? = null
) {

    companion object {
        private const val TAG = "FifoNavigationManager"
    }

    // ── Domain types ──────────────────────────────────────────────────────

    data class ActiveDestination(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val fullAddress: String,
        val road: String,
        val startedAtTimestamp: Long = System.currentTimeMillis()
    )

    data class NavigationGuidanceReport(
        val destinationName: String,
        val distanceMeters: Int,
        val walkingMinutes: Int,
        val blocks: Int,
        val targetBearing: Float,
        val cardinalDirection: String,
        val relativeInstruction: String,
        val isFacingTarget: Boolean,
        val streetName: String,
        val isArrived: Boolean,
        val isOffCourse: Boolean
    )

    /**
     * Estado conversacional de la sesión (encargos, pausas, correcciones).
     * Se vacía al iniciar o terminar una ruta.
     */
    val sessionMemory = NavigationSessionMemory()

    @Volatile
    var activeRoute: ActiveDestination? = null
        private set

    @Volatile
    var pendingDestination: NearbyPlaceMatch? = null
        private set

    val isNavigating: Boolean
        get() = activeRoute != null

    /** Callback emitted whenever the manager detects an autonomous navigation event. */
    var onAutonomousGuidance: ((NavigationNotice) -> Unit)? = null

    // ── GPS listener state (Android-only) ─────────────────────────────────

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Despacho de los avisos autónomos. En producción se entrega en el hilo
     * principal; en pruebas JVM se sustituye por una entrega síncrona porque el
     * `Handler` es un stub sin Looper.
     */
    internal var noticeDispatch: ((() -> Unit) -> Unit) = { block -> mainHandler.post(block) }

    init {
        // Cada mutación de la sesión (encargo, pausa, corrección, reset) deja su
        // foto en disco; el manager es el único dueño de la ruta (ADR-0041).
        sessionMemory.onChanged = { persistSession() }
    }

    // ── Route telemetry ───────────────────────────────────────────────────

    private var minDistanceReachedMeters = Int.MAX_VALUE
    private var lastAnnouncedTimestamp = 0L
    private var lastLocationTimestamp = 0L
    private var stationaryStartTimestamp = 0L
    private var stationaryAlertTriggered = false
    private var deviationAlertTriggered = false
    private var lastKnownLocation: Location? = null
    private val announcedMilestones = mutableSetOf<Int>()

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Saves the last place found by MapsSkill while waiting for confirmation ("Sí, guíame").
     */
    fun setPendingDestination(place: NearbyPlaceMatch) {
        pendingDestination = place
        Log.i(TAG, "Destino pendiente guardado para confirmación inmediata: ${place.name} (${place.road})")
    }

    /**
     * Starts an active navigation session toward the specified destination.
     */
    fun startNavigation(
        name: String,
        latitude: Double,
        longitude: Double,
        fullAddress: String,
        road: String
    ) {
        activeRoute = ActiveDestination(
            name = name,
            latitude = latitude,
            longitude = longitude,
            fullAddress = fullAddress,
            road = road
        )
        pendingDestination = null
        resetTelemetry()
        sessionMemory.reset()
        Log.i(TAG, "Navegación activa iniciada hacia: $name ($latitude, $longitude)")
        startContinuousGps()
    }

    /**
     * Confirms the pending destination and immediately starts active navigation.
     */
    fun confirmPendingDestination(): ActiveDestination? {
        val pending = pendingDestination ?: return null
        val route = ActiveDestination(
            name = pending.name,
            latitude = pending.latitude,
            longitude = pending.longitude,
            fullAddress = pending.fullAddressText,
            road = pending.road.ifBlank { pending.fullAddressText }
        )
        activeRoute = route
        pendingDestination = null
        minDistanceReachedMeters = pending.distanceMeters.coerceAtLeast(10)
        stationaryStartTimestamp = 0L
        stationaryAlertTriggered = false
        deviationAlertTriggered = false
        announcedMilestones.clear()
        lastAnnouncedTimestamp = System.currentTimeMillis()
        sessionMemory.reset()
        Log.i(TAG, "Destino pendiente confirmado exitosamente: ${route.name}")
        startContinuousGps()
        return route
    }

    /**
     * Stops the active navigation session.
     */
    fun stopNavigation() {
        Log.i(TAG, "Navegación detenida para: ${activeRoute?.name}")
        activeRoute = null
        pendingDestination = null
        stationaryStartTimestamp = 0L
        sessionMemory.reset()
        stopContinuousGps()
    }

    /**
     * Restaura la ruta y la sesión conversacional guardadas antes del cierre de
     * la app (ADR-0041). Es idempotente: si ya hay una ruta activa no toca nada.
     *
     * Una foto más vieja que [PersistedNavigationSession.DEFAULT_TTL_MILLIS] se
     * descarta: pasado ese plazo ya no es el viaje que la persona está haciendo.
     */
    fun restorePersistedSession(nowMillis: Long = System.currentTimeMillis()): ActiveDestination? {
        val store = sessionStore ?: return null
        activeRoute?.let { return it }

        val saved = store.load() ?: return null
        if (saved.isExpired(nowMillis)) {
            store.clear()
            Log.i(TAG, "Sesión de navegación guardada caducada; se descarta (${saved.destinationName})")
            return null
        }

        val route = ActiveDestination(
            name = saved.destinationName,
            latitude = saved.latitude,
            longitude = saved.longitude,
            fullAddress = saved.fullAddress,
            road = saved.road,
            startedAtTimestamp = saved.routeStartedAtMillis
        )
        activeRoute = route
        pendingDestination = null
        resetTelemetry()
        sessionMemory.restore(
            NavigationSessionMemory.Snapshot(
                stops = saved.stops.map {
                    NavigationSessionMemory.PlannedStop(it.label, it.addedAtMillis)
                },
                pause = saved.pauseStartedAtMillis.takeIf { it > 0L }?.let { startedAt ->
                    NavigationSessionMemory.Pause(
                        reason = saved.pauseReason,
                        untilMillis = saved.pauseUntilMillis,
                        startedAtMillis = startedAt
                    )
                },
                lastCorrection = saved.lastCorrection,
                lastCorrectionAtMillis = saved.lastCorrectionAtMillis
            )
        )
        Log.i(TAG, "Ruta restaurada tras reinicio: ${route.name} (${route.latitude}, ${route.longitude})")
        startContinuousGps()
        return route
    }

    /**
     * Test hook: drives the navigation state machine with a synthetic location
     * without needing real GPS hardware.
     */
    fun simulateLocationTick(currentLocation: Location) {
        onLocationTick(currentLocation)
    }

    // ── Guidance queries ──────────────────────────────────────────────────

    /**
     * Generates a detailed real-time report with distance, bearing and compass orientation.
     */
    fun getGuidanceReport(currentLoc: CurrentLocationInfo): NavigationGuidanceReport? {
        val dest = activeRoute ?: return null

        val distResults = FloatArray(2)
        Location.distanceBetween(
            currentLoc.latitude,
            currentLoc.longitude,
            dest.latitude,
            dest.longitude,
            distResults
        )

        val distanceMeters = distResults[0].toInt().coerceAtLeast(10)
        val targetBearing = (distResults[1] + 360f) % 360f
        val cardinal = locationAdapter.calculateCardinal(targetBearing)
        val walkingMinutes = (distanceMeters / 75).coerceAtLeast(1)
        val blocks = (distanceMeters / 100).coerceAtLeast(1)

        val userAzimuth = FifoOrientationHelper.currentAzimuthDegrees
        val turnInfo = FifoOrientationHelper.getRelativeTurnInstruction(userAzimuth, targetBearing)
        val isArrived = distanceMeters <= 25
        val isOffCourse = distanceMeters > minDistanceReachedMeters + 30

        return NavigationGuidanceReport(
            destinationName = dest.name,
            distanceMeters = distanceMeters,
            walkingMinutes = walkingMinutes,
            blocks = blocks,
            targetBearing = targetBearing,
            cardinalDirection = cardinal,
            relativeInstruction = turnInfo.instruction,
            isFacingTarget = turnInfo.isFacingTarget,
            streetName = dest.road.ifBlank { dest.fullAddress },
            isArrived = isArrived,
            isOffCourse = isOffCourse
        )
    }

    /**
     * Builds a context block to inject into the Groq system prompt.
     */
    fun buildSystemPromptContext(currentLoc: CurrentLocationInfo): String {
        val dest = activeRoute
        val heading = FifoOrientationHelper.currentCardinalHeading
        val degrees = FifoOrientationHelper.currentAzimuthDegrees.toInt()
        val spatialHeader = """
        === CONCIENCIA ESPACIAL DEL ENTORNO EN TIEMPO REAL ===
        - UBICACIÓN ACTUAL DEL USUARIO: ${currentLoc.address} (Comuna: ${currentLoc.city})
        - ORIENTACIÓN BRÚJULA / CELULAR: El usuario apunta físicamente hacia $heading ($degrees°).
        """.trimIndent()

        val sessionContext = sessionMemory.describeForPrompt()
        fun withSession(block: String): String =
            if (sessionContext.isBlank()) block else "$block\n$sessionContext"

        if (dest == null) {
            val pending = pendingDestination
            if (pending != null) {
                return withSession(
                    """
                    $spatialHeader
                    === DESTINO CONSULTADO RECIENTEMENTE (PENDIENTE DE CONFIRMACIÓN) ===
                    - LUGAR: ${pending.name} (${pending.fullAddressText})
                    - DISTANCIA: ${pending.distanceMeters} metros (${pending.walkingMinutes} min caminando).
                    - REGLA DE DIÁLOGO: Si el usuario responde afirmativamente ("sí", "guíame", "vamos", "porfa"), confirma con entusiasmo e inicia el acompañamiento indicando hacia dónde caminar sin volver a repetir toda la presentación ni preguntar de nuevo.
                    """.trimIndent()
                )
            }
            return withSession(spatialHeader)
        }

        val report = getGuidanceReport(currentLoc) ?: return withSession(spatialHeader)
        val distText = if (report.distanceMeters < 1200) "${report.distanceMeters} metros"
            else "${String.format(Locale("es", "ES"), "%.1f", report.distanceMeters / 1000.0)} km"

        return withSession(
            """
            $spatialHeader
            === MODO NAVEGACIÓN ACTIVA EN TIEMPO REAL (ACOMPAÑANTE TIPO WAZE) ===
            - DESTINO ACTUAL: ${report.destinationName} (${dest.fullAddress})
            - CALLE PRINCIPAL: ${report.streetName}
            - DISTANCIA RESTANTE: $distText (aprox ${report.walkingMinutes} min a pie, ${report.blocks} cuadras).
            - RUMBO HACIA EL DESTINO: ${report.cardinalDirection} (${report.targetBearing.toInt()}°).
            - INDICACIÓN RELATIVA SEGÚN HACIA DÓNDE MIRA EL USUARIO: "${report.relativeInstruction}".
            - ESTADO DE RUTA: ${if (report.isOffCourse) "¡EL USUARIO SE DESVIÓ DEL CAMINO!" else "En camino correcto hacia el destino."}
            - REGLA DE ORO DE ACOMPAÑAMIENTO: El usuario ESTÁ EN CAMINO hacia este destino. Si pregunta "¿a dónde voy?", "¿y ahora?", "¿por dónde sigo?", respóndele directamente con base en su orientación actual y la distancia restante (ej: "Para ir al ${report.destinationName}, ${report.relativeInstruction} y camina unas ${report.blocks} cuadras por ${report.streetName}"). NUNCA digas que no te acuerdas ni inventes otra dirección.
            - SI EL USUARIO CORRIGE LA RUTA O INDICA QUE FIFO SE EQUIVOCÓ: reconócelo, registra la corrección con 'manage_navigation_session' (action=correct) y reorienta con la información nueva y su orientación actual; si no alcanza el contexto, investiga con 'web_search' antes de afirmar una calle.
            - SI HABLA DE OTRA COSA: responde esa conversación sin perder la ruta; retómala cuando corresponda o cuando el usuario lo pida.
            """.trimIndent()
        )
    }

    // ── GPS listener (Android SDK, skipped in JVM tests) ─────────────────

    @SuppressLint("MissingPermission")
    private fun startContinuousGps() {
        val lm = locationManagerProvider() ?: return
        locationManager = lm
        if (locationListener != null) return

        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) = onLocationTick(loc)

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationListener = listener

            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500L, 2.0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 3.0f, listener, Looper.getMainLooper())
            }
            Log.i(TAG, "Oyente GPS continuo iniciado para navegación autónoma")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar listener GPS continuo: ${e.message}")
        }
    }

    private fun stopContinuousGps() {
        val lm = locationManager ?: return
        val listener = locationListener ?: return
        try {
            lm.removeUpdates(listener)
            locationListener = null
            Log.i(TAG, "Oyente GPS continuo detenido")
        } catch (e: Exception) {
            Log.w(TAG, "Error removiendo listener GPS: ${e.message}")
        }
    }

    // ── Location tick (core navigation state machine) ─────────────────────

    /**
     * Processes each GPS position update while actively navigating.
     * Autonomously evaluates deviation, stationary detection, arrival, and milestones.
     */
    private fun onLocationTick(currentLocation: Location) {
        val dest = activeRoute ?: return
        val now = System.currentTimeMillis()

        val distResults = FloatArray(2)
        Location.distanceBetween(
            currentLocation.latitude,
            currentLocation.longitude,
            dest.latitude,
            dest.longitude,
            distResults
        )

        val distanceMeters = distResults[0].toInt().coerceAtLeast(5)
        val targetBearing = (distResults[1] + 360f) % 360f
        val userAzimuth = FifoOrientationHelper.currentAzimuthDegrees
        val turnInfo = FifoOrientationHelper.getRelativeTurnInstruction(userAzimuth, targetBearing)
        val cardinal = locationAdapter.calculateCardinal(targetBearing)
        val street = dest.road.ifBlank { dest.fullAddress }

        if (minDistanceReachedMeters == Int.MAX_VALUE) {
            minDistanceReachedMeters = distanceMeters
        }

        val speed = if (currentLocation.hasSpeed()) currentLocation.speed else {
            val last = lastKnownLocation
            if (last != null && lastLocationTimestamp > 0) {
                val dtSec = (now - lastLocationTimestamp) / 1000.0f
                if (dtSec > 0.5f) last.distanceTo(currentLocation) / dtSec else 0f
            } else 0f
        }

        lastKnownLocation = currentLocation
        lastLocationTimestamp = now

        // 1. ARRIVAL (< 25 m). Se anuncia incluso en pausa: es el objetivo del viaje.
        if (distanceMeters <= 25) {
            Log.i(TAG, "Llegada al destino detectada autónomamente ($distanceMeters m)")
            emitNotice(
                NavigationNotice(
                    kind = NavigationNoticeKind.ARRIVED,
                    destinationName = dest.name,
                    destinationAddress = dest.fullAddress,
                    streetName = street,
                    distanceMeters = distanceMeters,
                    blocks = 1,
                    walkingMinutes = 1,
                    relativeInstruction = turnInfo.instruction,
                    cardinalDirection = cardinal,
                    isFacingTarget = turnInfo.isFacingTarget
                )
            )
            stopNavigation()
            return
        }

        // 2. PAUSA CONSCIENTE: el usuario se detuvo a propósito (comprar, conversar,
        //    descansar). No se le interrumpe con avisos de desvío ni de quietud; se
        //    mantiene la referencia de distancia para no disparar un falso desvío al
        //    reanudar. El LLM sigue teniendo el contexto de la pausa.
        if (sessionMemory.isPaused()) {
            stationaryStartTimestamp = 0L
            stationaryAlertTriggered = false
            deviationAlertTriggered = false
            minDistanceReachedMeters = distanceMeters
            return
        }

        // 3. STATIONARY > 45 s
        val isStationary = speed < 0.45f
        if (isStationary) {
            if (stationaryStartTimestamp == 0L) {
                stationaryStartTimestamp = now
            } else {
                val stationarySec = (now - stationaryStartTimestamp) / 1000L
                val timeSinceSpeech = now - lastAnnouncedTimestamp
                if (stationarySec >= 45 && !stationaryAlertTriggered && timeSinceSpeech >= 40_000L) {
                    stationaryAlertTriggered = true
                    lastAnnouncedTimestamp = now
                    emitNotice(
                        NavigationNotice(
                            kind = NavigationNoticeKind.STALLED,
                            destinationName = dest.name,
                            destinationAddress = dest.fullAddress,
                            streetName = street,
                            distanceMeters = distanceMeters,
                            blocks = (distanceMeters / 100).coerceAtLeast(1),
                            walkingMinutes = (distanceMeters / 75).coerceAtLeast(1),
                            relativeInstruction = turnInfo.instruction,
                            cardinalDirection = cardinal,
                            isFacingTarget = turnInfo.isFacingTarget,
                            stalledSeconds = stationarySec.toInt()
                        )
                    )
                }
            }
        } else {
            stationaryStartTimestamp = 0L
            stationaryAlertTriggered = false

            // 4. DEVIATION (distance > min reached + 35 m)
            if (distanceMeters > minDistanceReachedMeters + 35) {
                val timeSinceSpeech = now - lastAnnouncedTimestamp
                if (!deviationAlertTriggered && timeSinceSpeech >= 30_000L) {
                    deviationAlertTriggered = true
                    minDistanceReachedMeters = distanceMeters
                    lastAnnouncedTimestamp = now
                    emitNotice(
                        NavigationNotice(
                            kind = NavigationNoticeKind.OFF_COURSE,
                            destinationName = dest.name,
                            destinationAddress = dest.fullAddress,
                            streetName = street,
                            distanceMeters = distanceMeters,
                            blocks = (distanceMeters / 100).coerceAtLeast(1),
                            walkingMinutes = (distanceMeters / 75).coerceAtLeast(1),
                            relativeInstruction = turnInfo.instruction,
                            cardinalDirection = cardinal,
                            isFacingTarget = turnInfo.isFacingTarget
                        )
                    )
                }
            } else if (distanceMeters < minDistanceReachedMeters) {
                minDistanceReachedMeters = distanceMeters
                deviationAlertTriggered = false
            }

            // 5. PROXIMITY MILESTONES (100 m and 50 m)
            val timeSinceSpeech = now - lastAnnouncedTimestamp
            if (distanceMeters in 35..65 && !announcedMilestones.contains(50) && timeSinceSpeech >= 20_000L) {
                announcedMilestones.add(50)
                lastAnnouncedTimestamp = now
                emitNotice(
                    NavigationNotice(
                        kind = NavigationNoticeKind.APPROACHING_50M,
                        destinationName = dest.name,
                        destinationAddress = dest.fullAddress,
                        streetName = street,
                        distanceMeters = distanceMeters,
                        blocks = 1,
                        walkingMinutes = 1,
                        relativeInstruction = turnInfo.instruction,
                        cardinalDirection = cardinal,
                        isFacingTarget = turnInfo.isFacingTarget
                    )
                )
            } else if (distanceMeters in 85..130 && !announcedMilestones.contains(100) && timeSinceSpeech >= 25_000L) {
                announcedMilestones.add(100)
                lastAnnouncedTimestamp = now
                emitNotice(
                    NavigationNotice(
                        kind = NavigationNoticeKind.APPROACHING_100M,
                        destinationName = dest.name,
                        destinationAddress = dest.fullAddress,
                        streetName = street,
                        distanceMeters = distanceMeters,
                        blocks = (distanceMeters / 100).coerceAtLeast(1),
                        walkingMinutes = (distanceMeters / 75).coerceAtLeast(1),
                        relativeInstruction = turnInfo.instruction,
                        cardinalDirection = cardinal,
                        isFacingTarget = turnInfo.isFacingTarget
                    )
                )
            }
        }
    }

    /**
     * Publica el aviso estructurado. El motor no redacta texto: el pipeline pide
     * al LLM que lo componga con el contexto de la sesión y solo usa
     * [NavigationNotice.fallbackSpeech] si el modelo no responde.
     */
    private fun emitNotice(notice: NavigationNotice) {
        val enriched = notice.copy(
            plannedStops = sessionMemory.pendingStops().map { it.label },
            pauseReason = sessionMemory.pause?.reason.orEmpty()
        )
        Log.i(TAG, "Aviso de navegación autónomo: ${enriched.kind} (${enriched.distanceMeters} m)")
        noticeDispatch { onAutonomousGuidance?.invoke(enriched) }
    }

    /**
     * Guarda la foto de la ruta y su sesión. Sin ruta activa no hay nada que
     * restaurar, así que se limpia lo persistido (fin de ruta, llegada o reset).
     */
    private fun persistSession() {
        val store = sessionStore ?: return
        val route = activeRoute
        if (route == null) {
            store.clear()
            return
        }

        val snapshot = sessionMemory.snapshot()
        store.save(
            PersistedNavigationSession(
                savedAtMillis = System.currentTimeMillis(),
                destinationName = route.name,
                latitude = route.latitude,
                longitude = route.longitude,
                fullAddress = route.fullAddress,
                road = route.road,
                routeStartedAtMillis = route.startedAtTimestamp,
                stops = snapshot.stops.map { PersistedStop(it.label, it.addedAtMillis) },
                pauseReason = snapshot.pause?.reason.orEmpty(),
                pauseUntilMillis = snapshot.pause?.untilMillis,
                pauseStartedAtMillis = snapshot.pause?.startedAtMillis ?: 0L,
                lastCorrection = snapshot.lastCorrection,
                lastCorrectionAtMillis = snapshot.lastCorrectionAtMillis
            )
        )
    }

    private fun resetTelemetry() {
        minDistanceReachedMeters = Int.MAX_VALUE
        stationaryStartTimestamp = 0L
        stationaryAlertTriggered = false
        deviationAlertTriggered = false
        announcedMilestones.clear()
        lastAnnouncedTimestamp = System.currentTimeMillis()
    }
}
