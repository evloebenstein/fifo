package com.fifo.voicepipeline.pipeline.session.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tabla de verdad del canal telefónico simulado (ADR-0048). No mide campo:
 * comprueba que el filtro recorta donde dice, que el AGC no recorta picos y
 * que el perfil es determinista, para que un informe se pueda reproducir.
 */
class TelephonyChannelTest {

    private fun sine(freqHz: Double, samples: Int = 16_000, amplitude: Double = 0.5): ShortArray =
        ShortArray(samples) {
            (amplitude * sin(2.0 * PI * freqHz * it / 16_000.0) * 32767.0).toInt().toShort()
        }

    /** RMS en dBFS ignorando el transitorio del filtro. */
    private fun levelDb(pcm: ShortArray, skip: Int = 400): Double {
        var sum = 0.0
        var count = 0
        for (index in skip until pcm.size) {
            val value = pcm[index] / 32768.0
            sum += value * value
            count++
        }
        return 20.0 * ln(sqrt(sum / count)) / ln(10.0)
    }

    private fun attenuationDb(freqHz: Double, band: TelephonyBand): Double {
        val input = sine(freqHz)
        val filtered = TelephonyChannel.bandLimit(input, band)
            .let { signal -> ShortArray(signal.size) { (signal[it] * 32767.0).toInt().toShort() } }
        return levelDb(filtered) - levelDb(input)
    }

    @Test
    fun `la banda estrecha corta graves y agudos y deja pasar la voz`() {
        val voice = attenuationDb(1_000.0, TelephonyBand.NARROWBAND)
        val low = attenuationDb(100.0, TelephonyBand.NARROWBAND)
        val high = attenuationDb(6_000.0, TelephonyBand.NARROWBAND)

        assertTrue("1 kHz debería pasar casi intacto, fue $voice dB", abs(voice) <= 1.0)
        assertTrue("100 Hz debería caer al menos 12 dB, cayó $low dB", low <= -12.0)
        assertTrue("6 kHz debería caer al menos 8 dB, cayó $high dB", high <= -8.0)
    }

    @Test
    fun `la banda ancha conserva los agudos`() {
        val high = attenuationDb(6_000.0, TelephonyBand.WIDEBAND)
        val low = attenuationDb(100.0, TelephonyBand.WIDEBAND)
        assertTrue("6 kHz debería quedar a menos de 3 dB, quedó $high dB", high >= -3.0)
        assertTrue("100 Hz debería quedar a menos de 3 dB, quedó $low dB", low >= -3.0)
    }

    @Test
    fun `el AGC lleva el nivel al objetivo y no recorta`() {
        val profile = ChannelProfile(TelephonyBand.WIDEBAND, tiltDb = 0.0, targetRmsDbfs = -26.0)
        val output = TelephonyChannel.apply(sine(440.0, amplitude = 0.05), profile)

        val rms = TelephonyChannel.rmsDbfs(output)
        assertTrue("RMS $rms dBFS fuera del objetivo -26 ± 1.5 dB", abs(rms + 26.0) <= 1.5)

        var peak = 0.0
        for (sample in output) peak = maxOf(peak, abs(sample / 32768.0))
        assertTrue("el pico $peak debería quedar bajo 0,95", peak <= 0.95)
    }

    @Test
    fun `el perfil es determinista y se mueve en el rango de un movil`() {
        val profiles = (0 until 20).map { TelephonyChannel.profile(it) }
        assertEquals(profiles, (0 until 20).map { TelephonyChannel.profile(it) })
        assertTrue("deberían aparecer las dos bandas", profiles.map { it.band }.toSet().size == 2)
        for (profile in profiles) {
            assertTrue("tilt ${profile.tiltDb} fuera de ±4 dB", abs(profile.tiltDb) <= 4.0)
            assertTrue("RMS ${profile.targetRmsDbfs} fuera de −30..−22", profile.targetRmsDbfs in -30.0..-22.0)
        }
    }

    @Test
    fun `la banda se puede reconstruir desde el manifiesto`() {
        for (band in TelephonyBand.entries) {
            assertEquals(band, TelephonyBand.of(band.id))
        }
    }

    @Test
    fun `la cadena es determinista y conserva la longitud`() {
        val input = sine(300.0, samples = 4_000, amplitude = 0.3)
        val profile = TelephonyChannel.profile(7)
        val first = TelephonyChannel.apply(input, profile)
        val second = TelephonyChannel.apply(input, profile)

        assertArrayEquals(first, second)
        assertEquals(input.size, first.size)
        assertEquals(0, TelephonyChannel.apply(ShortArray(0), profile).size)
    }
}
