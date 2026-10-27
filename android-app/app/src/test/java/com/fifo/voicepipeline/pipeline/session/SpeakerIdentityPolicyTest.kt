package com.fifo.voicepipeline.pipeline.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla de verdad de [SpeakerIdentityPolicy] (ADR-0048).
 *
 * La política clasifica evidencia; no habla, no guarda perfiles y no autoriza
 * acciones. Estas pruebas fijan los cinco estados y el contexto textual que
 * recibe el LLM.
 */
class SpeakerIdentityPolicyTest {

    private val owner = profile("owner", "Ana")
    private val friend = profile("friend", "María")

    private fun profile(id: String, name: String) = VoiceProfile(
        id = id,
        displayName = name,
        embedding = floatArrayOf(1f, 0f),
        modelId = "test-model",
        enrolledAtMillis = 1_000L,
        sampleCount = 3
    )

    private fun evidence(
        vararg candidates: SpeakerCandidate,
        durationMillis: Long = 2_000L,
        modelId: String = "test-model"
    ) = SpeakerEvidence(
        candidates = candidates.toList(),
        durationMillis = durationMillis,
        observedAtMillis = 5_000L,
        modelId = modelId
    )

    private fun resolve(
        evidence: SpeakerEvidence?,
        ownerProfileId: String? = owner.id,
        config: SpeakerPolicyConfig = SpeakerPolicyConfig()
    ) = SpeakerIdentityPolicy.resolve(
        evidence = evidence,
        ownerProfileId = ownerProfileId,
        profiles = listOf(owner, friend),
        config = config
    )

    @Test
    fun `sin evidencia la identidad queda no disponible`() {
        val identity = resolve(null)

        assertEquals(SpeakerRelation.UNAVAILABLE, identity.relation)
        assertNull(identity.profileId)
        assertEquals(NOOP_SPEAKER_MODEL_ID, identity.modelId)
    }

    @Test
    fun `un audio demasiado corto no atribuye el turno`() {
        val identity = resolve(
            evidence(SpeakerCandidate(owner.id, 0.95), durationMillis = 500L)
        )

        assertEquals(SpeakerRelation.UNCERTAIN, identity.relation)
        assertNull(identity.profileId)
    }

    @Test
    fun `sin candidatos la identidad queda no disponible`() {
        val identity = resolve(evidence())

        assertEquals(SpeakerRelation.UNAVAILABLE, identity.relation)
    }

    @Test
    fun `una similitud baja es una voz desconocida`() {
        val identity = resolve(
            evidence(SpeakerCandidate(friend.id, 0.42))
        )

        assertEquals(SpeakerRelation.UNKNOWN, identity.relation)
        assertEquals(0.42, identity.confidence, 0.0001)
        assertNull(identity.profileId)
    }

    @Test
    fun `dos perfiles casi empatados son ambiguos`() {
        val identity = resolve(
            evidence(
                SpeakerCandidate(owner.id, 0.84),
                SpeakerCandidate(friend.id, 0.82)
            )
        )

        assertEquals(SpeakerRelation.UNCERTAIN, identity.relation)
    }

    @Test
    fun `la voz del usuario principal se marca como OWNER`() {
        val identity = resolve(
            evidence(
                SpeakerCandidate(owner.id, 0.93),
                SpeakerCandidate(friend.id, 0.51)
            )
        )

        assertEquals(SpeakerRelation.OWNER, identity.relation)
        assertEquals(owner.id, identity.profileId)
        assertEquals("Ana", identity.displayName)
        assertTrue(identity.isPersonal)
    }

    @Test
    fun `otro perfil enrolado se marca como KNOWN y no como OWNER`() {
        val identity = resolve(
            evidence(
                SpeakerCandidate(friend.id, 0.88),
                SpeakerCandidate(owner.id, 0.60)
            )
        )

        assertEquals(SpeakerRelation.KNOWN, identity.relation)
        assertEquals(friend.id, identity.profileId)
        assertEquals("María", identity.displayName)
    }

    @Test
    fun `sin dueño configurado un perfil sigue siendo KNOWN`() {
        val identity = resolve(
            evidence(SpeakerCandidate(owner.id, 0.93)),
            ownerProfileId = null
        )

        assertEquals(SpeakerRelation.KNOWN, identity.relation)
    }

    @Test
    fun `los umbrales son configurables por modelo`() {
        val strict = SpeakerPolicyConfig(minAcceptScore = 0.90)
        val lenient = SpeakerPolicyConfig(minAcceptScore = 0.40)

        assertEquals(
            SpeakerRelation.UNKNOWN,
            resolve(evidence(SpeakerCandidate(friend.id, 0.80)), config = strict).relation
        )
        assertEquals(
            SpeakerRelation.KNOWN,
            resolve(evidence(SpeakerCandidate(friend.id, 0.80)), config = lenient).relation
        )
    }

    @Test
    fun `la evidencia exige candidatos ordenados y puntajes validos`() {
        assertThrows(IllegalArgumentException::class.java) {
            evidence(
                SpeakerCandidate(owner.id, 0.50),
                SpeakerCandidate(friend.id, 0.80)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeakerCandidate(owner.id, 1.2)
        }
    }

    @Test
    fun `el prompt del dueño puede personalizar con su nombre`() {
        val prompt = SpeakerPromptContext.build(
            SpeakerIdentity(
                relation = SpeakerRelation.OWNER,
                profileId = owner.id,
                displayName = "Ana",
                confidence = 0.93,
                modelId = "test-model"
            )
        )

        assertTrue(prompt!!.contains("Ana"))
        assertTrue(prompt.contains("usuario principal"))
    }

    @Test
    fun `el prompt de un invitado prohibe revelar datos privados`() {
        val known = SpeakerPromptContext.build(
            SpeakerIdentity(
                relation = SpeakerRelation.KNOWN,
                profileId = friend.id,
                displayName = "María",
                confidence = 0.88,
                modelId = "test-model"
            )
        )
        val unknown = SpeakerPromptContext.build(
            SpeakerIdentity(SpeakerRelation.UNKNOWN, confidence = 0.30, modelId = "test-model")
        )
        val uncertain = SpeakerPromptContext.build(
            SpeakerIdentity(SpeakerRelation.UNCERTAIN, confidence = 0.75, modelId = "test-model")
        )

        assertTrue(known!!.contains("no mezcles"))
        assertTrue(known.contains("datos suyos"))
        assertTrue(unknown!!.contains("no reveles datos privados"))
        assertTrue(uncertain!!.contains("sin confirmación"))
    }

    @Test
    fun `sin identidad no se inyecta contexto de locutor`() {
        assertNull(SpeakerPromptContext.build(null))
        assertNull(SpeakerPromptContext.build(SpeakerIdentity.unavailable()))
    }
}
