package com.fifo.voicepipeline.pipeline.session.speaker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Canal telefónico simulado para el campo simulado en español (ADR-0048).
 *
 * El corpus aumentado comparte micrófono y sala por locutor: los clips de una
 * persona salen de una sola grabación. Un verificador puede aprender ese canal
 * en vez del timbre, y el número sale mejor de lo que será una llamada. Esta
 * pieza añade lo que faltaba en la cadena: banda, coloración de micrófono y AGC
 * **por clip**, de forma que la sesión de uso no coincide con la de enrolamiento.
 *
 * No sustituye a una grabación de campo. El canal original de la charla sigue
 * ahí y no se puede quitar; por eso el resultado es una **estimación dura**, no
 * un certificado. Tampoco mete códec: eso lo hace `apply_telephony_codec.py`
 * sobre el corpus ya exportado, porque el códec es transporte, no acústica.
 */
internal enum class TelephonyBand(val id: String, val lowHz: Double, val highHz: Double) {
    /** PSTN/GSM: 300–3400 Hz. */
    NARROWBAND("narrowband", 300.0, 3400.0),

    /** VoLTE/Opus: 50–7000 Hz. */
    WIDEBAND("wideband", 50.0, 7000.0);

    companion object {
        fun of(id: String): TelephonyBand = entries.firstOrNull { it.id == id }
            ?: error("banda desconocida: $id")
    }
}

/** Perfil concreto de un clip: banda, inclinación espectral y nivel de AGC. */
internal data class ChannelProfile(
    val band: TelephonyBand,
    val tiltDb: Double,
    val targetRmsDbfs: Double
)

internal object TelephonyChannel {

    private const val SAMPLE_RATE = 16_000.0
    private const val PEAK_LIMIT = 0.95

    /**
     * Perfil determinista a partir de una semilla. La mitad son llamadas
     * estrechas y la mitad anchas, con ±4 dB de inclinación y AGC entre −30 y
     * −22 dBFS: el rango de un móvil, no el de un estudio.
     */
    fun profile(seed: Int): ChannelProfile {
        val rng = Random(seed)
        val band = if (rng.nextDouble() < 0.5) TelephonyBand.NARROWBAND else TelephonyBand.WIDEBAND
        return ChannelProfile(
            band = band,
            tiltDb = rng.nextDouble(-4.0, 4.0),
            targetRmsDbfs = rng.nextDouble(-30.0, -22.0)
        )
    }

    /** Cadena completa: banda, coloración, AGC y límite de pico. */
    fun apply(pcm: ShortArray, profile: ChannelProfile): ShortArray {
        if (pcm.isEmpty()) return pcm
        var signal = bandLimit(pcm, profile.band)
        signal = tilt(signal, profile.tiltDb)
        signal = automaticGain(signal, profile.targetRmsDbfs)
        return toPcm(signal)
    }

    /** Paso 1: recorte de banda con dos biquads Butterworth (Q = 0.707). */
    fun bandLimit(pcm: ShortArray, band: TelephonyBand): DoubleArray {
        val signal = DoubleArray(pcm.size) { pcm[it] / 32768.0 }
        highPass(signal, band.lowHz)
        lowPass(signal, band.highHz)
        return signal
    }

    private fun highPass(signal: DoubleArray, cutoffHz: Double) {
        val (b0, b1, b2, a1, a2) = biquad(cutoffHz, highPass = true)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (index in signal.indices) {
            val x0 = signal[index]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            signal[index] = y0
        }
    }

    private fun lowPass(signal: DoubleArray, cutoffHz: Double) {
        val (b0, b1, b2, a1, a2) = biquad(cutoffHz, highPass = false)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (index in signal.indices) {
            val x0 = signal[index]
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            signal[index] = y0
        }
    }

    /**
     * Biquad RBJ con Q = 0.707. A 16 kHz el Butterworth de 2.º orden da ~12
     * dB/octava: suficiente para que un tono de 6 kHz caiga por debajo de la
     * banda estrecha sin inventar un filtro de orden alto.
     */
    private fun biquad(cutoffHz: Double, highPass: Boolean): Coefficients {
        val omega = 2.0 * PI * cutoffHz / SAMPLE_RATE
        val alpha = sin(omega) / (2.0 * 0.707)
        val cosOmega = cos(omega)
        val a0 = 1.0 + alpha
        val b0: Double
        val b1: Double
        val b2: Double
        if (highPass) {
            b0 = (1.0 + cosOmega) / 2.0
            b1 = -(1.0 + cosOmega)
            b2 = (1.0 + cosOmega) / 2.0
        } else {
            b0 = (1.0 - cosOmega) / 2.0
            b1 = 1.0 - cosOmega
            b2 = (1.0 - cosOmega) / 2.0
        }
        return Coefficients(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = (-2.0 * cosOmega) / a0,
            a2 = (1.0 - alpha) / a0
        )
    }

    /**
     * Paso 2: inclinación espectral. Separa con un paso bajo de un polo a
     * 700 Hz y reparte ganancia entre grave y agudo. Un micrófono de móvil no
     * es plano, y dos móviles no colorean igual.
     */
    private fun tilt(signal: DoubleArray, tiltDb: Double): DoubleArray {
        if (tiltDb == 0.0) return signal
        val alpha = 1.0 - kotlin.math.exp(-2.0 * PI * 700.0 / SAMPLE_RATE)
        val gainLow = Math.pow(10.0, tiltDb / 20.0)
        var low = 0.0
        return DoubleArray(signal.size) { index ->
            low += alpha * (signal[index] - low)
            signal[index] + low * (gainLow - 1.0)
        }
    }

    /** Paso 3: AGC. Lleva el RMS al objetivo y limita el pico sin recortar. */
    private fun automaticGain(signal: DoubleArray, targetRmsDbfs: Double): DoubleArray {
        var sum = 0.0
        for (value in signal) sum += value * value
        val rms = sqrt(sum / signal.size)
        if (rms <= 1e-9) return signal
        val target = Math.pow(10.0, targetRmsDbfs / 20.0)
        val gain = (target / rms).coerceIn(0.1, 8.0)
        var peak = 0.0
        for (value in signal) peak = maxOf(peak, abs(value * gain))
        val limit = if (peak > PEAK_LIMIT) PEAK_LIMIT / peak else 1.0
        return DoubleArray(signal.size) { (signal[it] * gain * limit).coerceIn(-1.0, 1.0) }
    }

    private fun toPcm(signal: DoubleArray): ShortArray =
        ShortArray(signal.size) { (signal[it] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    /** RMS en dBFS, para tests y para leer el manifiesto. */
    fun rmsDbfs(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return -120.0
        var sum = 0.0
        for (sample in pcm) {
            val value = sample / 32768.0
            sum += value * value
        }
        if (sum <= 0.0) return -120.0
        return 20.0 * ln(sqrt(sum / pcm.size)) / ln(10.0)
    }
}
