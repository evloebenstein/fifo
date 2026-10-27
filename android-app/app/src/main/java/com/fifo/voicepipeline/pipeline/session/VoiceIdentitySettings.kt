package com.fifo.voicepipeline.pipeline.session

import android.content.Context

/**
 * Composición y consentimiento del reconocimiento de voz (ADR-0048).
 *
 * - Un único almacén cifrado por proceso, para que la UI y el pipeline lean y
 *   escriban el mismo archivo.
 * - El consentimiento vive en `fifo_prefs` y se consulta en cada lectura/escritura
 *   del almacén: retirarlo oculta los perfiles al instante, pero **siempre** se
 *   puede borrar.
 * - No construye ningún extractor real. Mientras no exista un modelo medido,
 *   [provider] devuelve `NoopSpeakerIdentityProvider` y la UI lo dice sin
 *   prometer un enrolamiento que no puede cumplir.
 */
object VoiceIdentitySettings {

    const val CONSENT_KEY = "voice_identity_consent"

    private const val PREFS_NAME = "fifo_prefs"

    @Volatile
    private var sharedStore: VoiceProfileStore? = null

    private val lock = Any()

    fun hasConsent(context: Context): Boolean =
        preferences(context).getBoolean(CONSENT_KEY, false)

    fun setConsent(context: Context, granted: Boolean) {
        preferences(context).edit().putBoolean(CONSENT_KEY, granted).apply()
    }

    /**
     * Almacén cifrado compartido. La puerta de consentimiento vive dentro del
     * propio almacén, así que no hace falta repetir la comprobación en la UI.
     */
    fun store(context: Context): VoiceProfileStore {
        sharedStore?.let { return it }
        return synchronized(lock) {
            sharedStore ?: androidEncryptedVoiceProfileStore(
                filesDir = context.applicationContext.filesDir,
                hasConsent = { hasConsent(context.applicationContext) }
            ).also { sharedStore = it }
        }
    }

    /**
     * Modelo activo del camino nuevo.
     *
     * `null` mientras no haya un extractor de embeddings empaquetado y medido.
     * La UI usa esto para no ofrecer un enrolamiento que solo guardaría basura.
     */
    fun modelId(): String? = null

    /** `true` cuando existe un extractor real; hoy siempre `false`. */
    val isEnrollmentAvailable: Boolean
        get() = modelId() != null

    /**
     * Proveedor que consume `VoiceSessionPipeline`.
     *
     * La decisión vive aquí y no en el facade: mientras [modelId] sea `null`
     * devuelve el `Noop` y todos los turnos quedan `UNAVAILABLE`. Cuando se
     * valide un extractor, este método construye el
     * `EmbeddingSpeakerIdentityProvider` con [store] y su detector de spoofing.
     */
    fun provider(): SpeakerIdentityProvider = NoopSpeakerIdentityProvider

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
