package com.fifo.voicepipeline.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de detección de alucinaciones de Whisper — incluyendo el modo context-aware
 * (allowShortResponses) que previene descartar respuestas reales de follow-up.
 */
class CloudApiClientHallucinationTest {

    // ── Alucinaciones absolutas ────────────────────────────────────────────────

    @Test
    fun `detecta alucinacion de YouTube - gracias por ver el video`() {
        assertTrue(CloudApiClient.isWhisperHallucination("Gracias por ver el video"))
    }

    @Test
    fun `detecta alucinacion de YouTube con tildes`() {
        assertTrue(CloudApiClient.isWhisperHallucination("Gracias por ver este video"))
    }

    @Test
    fun `detecta alucinacion de suscripcion`() {
        assertTrue(CloudApiClient.isWhisperHallucination("Dale like y suscríbete"))
    }

    @Test
    fun `detecta alucinacion de amara`() {
        assertTrue(CloudApiClient.isWhisperHallucination("Subtítulos por la comunidad de amara org"))
    }

    @Test
    fun `texto vacio es alucinacion`() {
        assertTrue(CloudApiClient.isWhisperHallucination(""))
    }

    @Test
    fun `texto de un caracter es alucinacion`() {
        assertTrue(CloudApiClient.isWhisperHallucination("a"))
    }

    // ── Palabras ambiguas — sin contexto de follow-up ─────────────────────────

    @Test
    fun `si sin contexto es alucinacion`() {
        assertTrue(CloudApiClient.isWhisperHallucination("si"))
    }

    @Test
    fun `no sin contexto es alucinacion`() {
        assertTrue(CloudApiClient.isWhisperHallucination("no"))
    }

    @Test
    fun `ok sin contexto es alucinacion`() {
        assertTrue(CloudApiClient.isWhisperHallucination("ok"))
    }

    @Test
    fun `gracias sin contexto es alucinacion`() {
        assertTrue(CloudApiClient.isWhisperHallucination("gracias"))
    }

    @Test
    fun `muchas gracias sin contexto es alucinacion`() {
        assertTrue(CloudApiClient.isWhisperHallucination("Muchas gracias"))
    }

    // ── Palabras ambiguas — CON contexto de follow-up → deben pasar ──────────

    @Test
    fun `si con allowShortResponses NO es alucinacion`() {
        assertFalse(CloudApiClient.isWhisperHallucination("sí", allowShortResponses = true))
    }

    @Test
    fun `no con allowShortResponses NO es alucinacion`() {
        assertFalse(CloudApiClient.isWhisperHallucination("no", allowShortResponses = true))
    }

    @Test
    fun `ok con allowShortResponses NO es alucinacion`() {
        assertFalse(CloudApiClient.isWhisperHallucination("ok", allowShortResponses = true))
    }

    @Test
    fun `gracias con allowShortResponses NO es alucinacion`() {
        assertFalse(CloudApiClient.isWhisperHallucination("gracias", allowShortResponses = true))
    }

    @Test
    fun `dale con allowShortResponses NO es alucinacion`() {
        assertFalse(CloudApiClient.isWhisperHallucination("dale", allowShortResponses = true))
    }

    @Test
    fun `claro con allowShortResponses NO es alucinacion`() {
        assertFalse(CloudApiClient.isWhisperHallucination("claro", allowShortResponses = true))
    }

    // ── Alucinaciones absolutas NO pasan nunca, ni con allowShortResponses ────

    @Test
    fun `gracias por ver el video es alucinacion aun con allowShortResponses`() {
        assertTrue(CloudApiClient.isWhisperHallucination("gracias por ver el video", allowShortResponses = true))
    }

    @Test
    fun `amara es alucinacion aun con allowShortResponses`() {
        assertTrue(CloudApiClient.isWhisperHallucination("amara", allowShortResponses = true))
    }

    // ── Frases reales de usuario siempre pasan ────────────────────────────────

    @Test
    fun `frase real pasa sin allowShortResponses`() {
        assertFalse(CloudApiClient.isWhisperHallucination("quiero ir a la farmacia"))
    }

    @Test
    fun `frase real pasa con allowShortResponses`() {
        assertFalse(CloudApiClient.isWhisperHallucination("llama a mi hija Carmen", allowShortResponses = true))
    }

    @Test
    fun `respuesta corta con contenido real no es alucinacion`() {
        assertFalse(CloudApiClient.isWhisperHallucination("a la farmacia"))
    }
}
