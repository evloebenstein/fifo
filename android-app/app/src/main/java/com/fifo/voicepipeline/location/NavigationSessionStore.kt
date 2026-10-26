package com.fifo.voicepipeline.location

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * Encargo en el camino tal como se persiste en disco (ADR-0041).
 *
 * Es un tipo plano, sin lógica, para que el contrato sea estable aunque
 * [NavigationSessionMemory] evolucione.
 */
data class PersistedStop(
    val label: String,
    val addedAtMillis: Long
)

/**
 * Foto durable de una ruta activa y de su estado conversacional.
 *
 * La escribe [FifoNavigationManager] en cada cambio de la sesión y se restaura
 * al arrancar el proceso para que cerrar la app no borre el acompañamiento.
 * [savedAtMillis] permite descartar sesiones viejas sin tocar la ruta en curso.
 */
data class PersistedNavigationSession(
    val savedAtMillis: Long,
    val destinationName: String,
    val latitude: Double,
    val longitude: Double,
    val fullAddress: String,
    val road: String,
    val routeStartedAtMillis: Long,
    val stops: List<PersistedStop> = emptyList(),
    val pauseReason: String = "",
    val pauseUntilMillis: Long? = null,
    val pauseStartedAtMillis: Long = 0L,
    val lastCorrection: String? = null,
    val lastCorrectionAtMillis: Long = 0L
) {

    /** `true` si la foto es más vieja que [ttlMillis] y ya no debe restaurarse. */
    fun isExpired(nowMillis: Long, ttlMillis: Long = DEFAULT_TTL_MILLIS): Boolean =
        nowMillis - savedAtMillis > ttlMillis

    companion object {
        /** Una ruta guardada caduca a las 6 h: pasado ese plazo ya no es el viaje. */
        const val DEFAULT_TTL_MILLIS: Long = 6 * 60 * 60 * 1000L
    }
}

/**
 * Costura de persistencia de la sesión de navegación (ADR-0041).
 *
 * El dominio ([FifoNavigationManager] y [NavigationSessionMemory]) no conoce
 * Android: solo habla con esta interfaz. Producción usa
 * [SharedPreferencesNavigationSessionStore]; las pruebas JVM usan
 * [InMemoryNavigationSessionStore].
 */
interface NavigationSessionStore {

    /** Última foto guardada, o `null` si no hay ruta que restaurar. */
    fun load(): PersistedNavigationSession?

    fun save(session: PersistedNavigationSession)

    fun clear()
}

/** Doble de prueba puro-JVM: guarda la foto en memoria. */
class InMemoryNavigationSessionStore : NavigationSessionStore {

    @Volatile
    var persisted: PersistedNavigationSession? = null
        private set

    override fun load(): PersistedNavigationSession? = persisted

    override fun save(session: PersistedNavigationSession) {
        persisted = session
    }

    override fun clear() {
        persisted = null
    }
}

/**
 * Persistencia real en `SharedPreferences` con Gson.
 *
 * El archivo es pequeño y se escribe en cada cambio de la sesión (encargo,
 * pausa, corrección, inicio o fin de ruta), así que se usa `apply()` para no
 * bloquear el hilo que atiende la herramienta o el tick de GPS.
 */
class SharedPreferencesNavigationSessionStore(
    context: Context,
    private val gson: Gson = Gson()
) : NavigationSessionStore {

    companion object {
        private const val TAG = "NavigationSessionStore"
        private const val PREFS_NAME = "fifo_navigation"
        private const val KEY_SESSION = "active_session"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PersistedNavigationSession? = runCatching {
        val raw = prefs.getString(KEY_SESSION, null) ?: return@runCatching null
        // Gson puede dejar campos nulos si el JSON quedó corrupto o cambió de
        // forma; cualquier foto inválida se descarta en vez de romper el arranque.
        gson.fromJson(raw, PersistedNavigationSession::class.java)
            ?.takeIf { session ->
                session.savedAtMillis > 0L &&
                    session.destinationName.isNotBlank() &&
                    session.fullAddress.isNotBlank() &&
                    session.latitude in -90.0..90.0 &&
                    session.longitude in -180.0..180.0 &&
                    !(session.latitude == 0.0 && session.longitude == 0.0)
            }
    }.getOrNull()

    override fun save(session: PersistedNavigationSession) {
        runCatching {
            prefs.edit()
                .putString(KEY_SESSION, gson.toJson(session))
                .apply()
        }.onFailure {
            Log.w(TAG, "No se pudo persistir la sesión de navegación: ${it.message}")
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }
}
