package com.fifo.voicepipeline.pipeline.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla de verdad de la política léxica compartida (ADR-0042).
 *
 * Es el contrato que usan el camino `SESSION` (`TurnOrchestrator`) y el legacy
 * (`VoicePipelineManager`): invocación, consulta, saludo, silencio, sueño,
 * eco, pregunta y control de llamada. Sin Android: se ejecuta en JVM.
 */
class UtterancePolicyTest {

    // ── Invocación ────────────────────────────────────────────────────────

    @Test
    fun `detecta Fifo y sus variantes foneticas`() {
        val positives = listOf(
            "Fifo",
            "fifo",
            "hola fifo",
            "fi fo",
            "Oye, ¿Fifo?",
            "fifooo",
            "fiiifo",
            "pifo",
            "feefo",
            "fefo",
            "cifo",
            "fifi"
        )
        positives.forEach { assertTrue("'$it' debería ser invocación", UtterancePolicy.isWakeWord(it)) }
    }

    @Test
    fun `no despierta con palabras parecidas de conversacion normal`() {
        val negatives = listOf(
            "tito",
            "kiko",
            "vivo",
            "pico",
            "hijo",
            "tipo",
            "dijo",
            "qué hora es",
            "¿cómo estás hoy?",
            "",
            "   "
        )
        negatives.forEach {
            assertFalse("'$it' NO debería despertar a Fifo", UtterancePolicy.isWakeWord(it))
        }
    }

    // ── Consulta y saludo ─────────────────────────────────────────────────

    @Test
    fun `extrae la consulta quitando la invocacion en cualquier posicion`() {
        assertEquals("qué hora es", UtterancePolicy.extractQuery("Fifo qué hora es"))
        assertEquals("llama a Carmen", UtterancePolicy.extractQuery("Hola Fifo llama a Carmen"))
        assertEquals("dime la hora", UtterancePolicy.extractQuery("dime la hora, Fifo"))
        assertEquals("¿me escuchas?", UtterancePolicy.extractQuery("Fifo, ¿me escuchas?"))
        assertEquals("", UtterancePolicy.extractQuery("Fifo"))
        assertEquals("", UtterancePolicy.extractQuery("Hola Fifo"))
    }

    @Test
    fun `distingue un saludo puro de una consulta`() {
        assertTrue(UtterancePolicy.isGreetingOnly("Fifo"))
        assertTrue(UtterancePolicy.isGreetingOnly("Hola Fifo"))
        assertTrue(UtterancePolicy.isGreetingOnly("Fifo buenas noches"))
        assertFalse(UtterancePolicy.isGreetingOnly("Fifo qué hora es"))
        assertFalse(UtterancePolicy.isGreetingOnly("Fifo, ¿cómo estás?"))
    }

    // ── Silencio ──────────────────────────────────────────────────────────

    @Test
    fun `reconoce ordenes explicitas de silencio`() {
        val orders = listOf(
            "silencio",
            "Fifo cállate",
            "Fifo cállate por favor",
            "cállate",
            "basta Fifo",
            "detente",
            "no hables",
            "no te estoy hablando",
            "Fifo, no te estoy hablando. Silencio.",
            "corta ya",
            "un momento",
            "para un momento",
            "espera",
            "alto"
        )
        orders.forEach {
            assertTrue("'$it' debe ser orden de silencio", UtterancePolicy.isSilenceCommand(it))
        }
    }

    @Test
    fun `no confunde la preposicion para con una orden de parar`() {
        val negatives = listOf(
            "para ir al centro",
            "quiero que me guíes para llegar",
            "busca el botón de silencio",
            "¿cómo estás hoy Fifo?",
            "para qué sirve"
        )
        negatives.forEach {
            assertFalse("'$it' NO debe detener a Fifo", UtterancePolicy.isSilenceCommand(it))
        }
    }

    @Test
    fun `acepta palabras cortas de detencion solo mientras Fifo habla`() {
        assertFalse(UtterancePolicy.isSilenceCommand("puedes parar"))
        assertTrue(UtterancePolicy.isSilenceCommand("puedes parar", whileSpeaking = true))
    }

    // ── Sueño ─────────────────────────────────────────────────────────────

    @Test
    fun `reconoce despedidas y ordenes de descanso`() {
        val orders = listOf(
            "hasta mañana",
            "a descansar",
            "deja de escuchar",
            "eso es todo",
            "buenas noches, descansa",
            "chao fifo",
            "Fifo apágate"
        )
        orders.forEach {
            assertTrue("'$it' debe ser orden de sueño", UtterancePolicy.isSleepCommand(it))
        }
        assertFalse(UtterancePolicy.isSleepCommand("buenas noches"))
        assertFalse(UtterancePolicy.isSleepCommand("fifo qué hora es"))
    }

    @Test
    fun `apagate es dormir y no un silencio momentaneo`() {
        assertFalse(UtterancePolicy.isSilenceCommand("Fifo apágate"))
        assertTrue(UtterancePolicy.isSleepCommand("Fifo apágate"))
    }

    // ── Eco ───────────────────────────────────────────────────────────────

    @Test
    fun `detecta el eco de la propia voz mientras habla`() {
        val spoken = "¿Quiere que le ponga música?"

        assertTrue(
            UtterancePolicy.isEcho(
                spokenSentence = spoken,
                text = "quiere que le ponga música",
                isSpeakingNow = true,
                awaitingAnswer = false
            )
        )
        assertTrue(
            UtterancePolicy.isEcho(
                spokenSentence = spoken,
                text = "música",
                isSpeakingNow = true,
                awaitingAnswer = false
            )
        )
    }

    @Test
    fun `fuera de locucion solo descarta repeticiones completas`() {
        val spoken = "El tiempo hoy es soleado con 22 grados"

        assertTrue(
            UtterancePolicy.isEcho(
                spokenSentence = spoken,
                text = spoken,
                isSpeakingNow = false,
                awaitingAnswer = false
            )
        )
        assertFalse(
            UtterancePolicy.isEcho(
                spokenSentence = spoken,
                text = "grados",
                isSpeakingNow = false,
                awaitingAnswer = false
            )
        )
    }

    @Test
    fun `nunca descarta una respuesta cuando Fifo espera contestacion`() {
        val spoken = "¿Prefiere agua o jugo?"

        assertFalse(
            UtterancePolicy.isEcho(
                spokenSentence = spoken,
                text = "agua",
                isSpeakingNow = false,
                awaitingAnswer = true
            )
        )
        assertFalse(
            UtterancePolicy.isEcho(
                spokenSentence = spoken,
                text = spoken,
                isSpeakingNow = false,
                awaitingAnswer = true
            )
        )
    }

    // ── Preguntas ─────────────────────────────────────────────────────────

    @Test
    fun `detecta preguntas sin signo de interrogacion`() {
        assertTrue(UtterancePolicy.isAskingQuestion("¿Te gustaría salir?"))
        assertTrue(UtterancePolicy.isAskingQuestion("Cuéntame"))
        assertTrue(UtterancePolicy.isAskingQuestion("Dime qué te gustaría desayunar"))
        assertFalse(UtterancePolicy.isAskingQuestion("El tiempo hoy es soleado con 22 grados."))
        assertFalse(UtterancePolicy.isAskingQuestion(""))
    }

    // ── Control de llamada ────────────────────────────────────────────────

    @Test
    fun `contestar y colgar exigen la invocacion completa`() {
        assertEquals(CallAction.ANSWER, UtterancePolicy.classifyCallAction("Fifo contesta", isRinging = true))
        assertEquals(CallAction.HANGUP, UtterancePolicy.classifyCallAction("Fifo cuelga", isRinging = false))
        assertEquals(
            CallAction.HANGUP,
            UtterancePolicy.classifyCallAction("Fifo cuelga la llamada", isRinging = false)
        )

        assertNull(UtterancePolicy.classifyCallAction("contesta", isRinging = true))
        assertNull(UtterancePolicy.classifyCallAction("cuelga", isRinging = false))
        assertNull(UtterancePolicy.classifyCallAction("cuelga la llamada", isRinging = false))
    }

    @Test
    fun `clasifica respuesta, colgado y estado del llamante`() {
        assertEquals(CallAction.ANSWER, UtterancePolicy.classifyCallAction("Fifo responde", isRinging = true))
        assertEquals(
            CallAction.CALLER_STATUS,
            UtterancePolicy.classifyCallAction("Fifo, ¿quién llama?", isRinging = true)
        )
        assertNull(UtterancePolicy.classifyCallAction("Fifo responde", isRinging = false))
    }
}
