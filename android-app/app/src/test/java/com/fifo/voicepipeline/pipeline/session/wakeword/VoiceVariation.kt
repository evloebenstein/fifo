package com.fifo.voicepipeline.pipeline.session.wakeword

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

/**
 * Variaciones de voz para el arnés de campo (ADR-0045).
 *
 * Dos huecos del corpus de ADR-0044: una persona con ruido **no habla igual**
 * (efecto Lombard) y el mundo no son seis adultos de laboratorio (niños, voces
 * grandes, acentos). Aquí se aproximan sin datos externos y sin binarios.
 *
 * - **Lombard** se aplica en el origen: más tono, formantes algo más altos,
 *   vocales más largas y un poco más de agudos (esfuerzo vocal). Después se
 *   reverbera, que es el orden físico correcto.
 * - **VTLP** (vocal tract length perturbation) escala los formantes: es
 *   exactamente lo que distingue a un niño de un adulto en el sintetizador.
 *   Sobre audio arbitrario el equivalente sería deformar la envolvente
 *   espectral; aquí, donde la voz se genera por formantes, se hace en la fuente
 *   y no hace falta aproximar.
 *
 * Ni una cosa ni la otra sustituyen a una grabación: sirven para que el arnés
 * deje de suponer que todo el mundo es un adulto calmado en una sala seca.
 */
internal object LombardEffect {

    /**
     * Proxy del efecto Lombard.
     *
     * Los valores salen de la dirección habitual en la literatura: +6 dB de
     * nivel, f0 +10 %, F1 +5 %, vocales +12 % y +3 dB de pendiente espectral.
     * No pretenden ser la respuesta de una persona concreta.
     */
    data class Profile(
        val levelDb: Double = 6.0,
        val f0Scale: Double = 1.10,
        val formantScale: Double = 1.05,
        val durationScale: Double = 1.12,
        val tiltDb: Double = 3.0
    ) {
        companion object {
            val DEFAULT = Profile()
        }
    }

    /** Variante del locutor que habla con esfuerzo. */
    fun adapt(speaker: SimulatedSpeaker, profile: Profile = Profile.DEFAULT): SimulatedSpeaker =
        speaker.copy(
            name = "${speaker.name}+lombard",
            f0Hz = speaker.f0Hz * profile.f0Scale,
            formantScale = speaker.formantScale * profile.formantScale,
            rate = speaker.rate / profile.durationScale
        )

    /**
     * Nivel y pendiente espectral del esfuerzo vocal.
     *
     * Se aplica sobre la señal seca, antes de la sala: el esfuerzo es de la
     * garganta, no de la pared.
     */
    fun applyEffort(pcm: ShortArray, profile: Profile = Profile.DEFAULT): ShortArray {
        val gain = 10.0.pow(profile.levelDb / 20.0)
        val highGain = 10.0.pow(profile.tiltDb / 20.0)
        val k = 1.0 - exp(-2.0 * PI * LOW_SHELF_HZ / SYNTH_SAMPLE_RATE)
        var low = 0.0

        return ShortArray(pcm.size) { index ->
            val sample = pcm[index] / 32768.0
            low += k * (sample - low)
            val high = sample - low
            val shaped = gain * (low + high * highGain)
            (shaped.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private const val LOW_SHELF_HZ = 800.0
}

internal object SpeakerVariation {

    /**
     * Ensimisma el tracto vocal: α > 1 suena a niño, α < 1 a una persona
     * grande. No cambia el ritmo, que es justo lo que distingue VTLP de una
     * simple perturbación de velocidad.
     */
    fun vtlp(speaker: SimulatedSpeaker, alpha: Double, name: String): SimulatedSpeaker {
        require(alpha > 0.5 && alpha < 1.6) { "α fuera de rango razonable: $alpha" }
        return speaker.copy(name = name, formantScale = speaker.formantScale * alpha)
    }

    /**
     * Perturbación de velocidad: al reproducir más rápido suben tono y
     * formantes y baja la duración. Es la augmentación clásica de habla y, a
     * diferencia de VTLP, no conserva el ritmo.
     */
    fun speed(speaker: SimulatedSpeaker, factor: Double, name: String): SimulatedSpeaker {
        require(factor > 0.5 && factor < 1.6) { "factor fuera de rango razonable: $factor" }
        return speaker.copy(
            name = name,
            f0Hz = speaker.f0Hz * factor,
            formantScale = speaker.formantScale * factor,
            rate = speaker.rate * factor
        )
    }
}
