package com.fifo.voicepipeline.pipeline.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato de [EmbeddingSpeakerIdentityProvider] y [SpeakerEnrollment] con un
 * extractor falso (ADR-0048).
 *
 * No mide calidad de voz: fija el flujo de enrolar → guardar centroide →
 * comparar por coseno → aplicar la política, sin depender de ONNX ni de pesos.
 */
class SpeakerIdentityProviderTest {

    private val ownerVector = floatArrayOf(1f, 0f, 0f)
    private val otherVector = floatArrayOf(0f, 1f, 0f)

    private val extractor = ScriptedExtractor(
        modelId = "fake-v1",
        embeddings = mapOf(1 to ownerVector, 2 to otherVector)
    )
    private val store = InMemoryVoiceProfileStore()

    private fun audio(marker: Int, size: Int = 64_000): ByteArray =
        ByteArray(size).also { it[0] = marker.toByte() }

    private fun provider(
        config: SpeakerPolicyConfig = SpeakerPolicyConfig(),
        ownerProfileId: String? = "owner",
        spoofingDetector: SpoofingDetector = NoopSpoofingDetector
    ) = EmbeddingSpeakerIdentityProvider(
        extractor = extractor,
        store = store,
        spoofingDetector = spoofingDetector,
        ownerProfileIdProvider = { ownerProfileId },
        config = config
    )

    private suspend fun enrollOwner() {
        SpeakerEnrollment(extractor, store, minSamples = 3).enroll(
            profileId = "owner",
            displayName = "Ana",
            samples = listOf(audio(1), audio(1), audio(1)),
            nowMillis = 1_000L
        )
    }

    private class FixedSpoofingDetector(
        private val assessment: SpoofingAssessment?
    ) : SpoofingDetector {
        override val modelId: String = assessment?.modelId ?: "spoof-v1"
        override suspend fun assess(wavData: ByteArray): SpoofingAssessment? = assessment
    }

    @Test
    fun `el enrolamiento guarda el centroide y no las muestras`() = runTest {
        val enrollment = SpeakerEnrollment(extractor, store, minSamples = 3)

        val profile = enrollment.enroll(
            profileId = "owner",
            displayName = "Ana",
            samples = listOf(audio(1), audio(1), audio(1)),
            nowMillis = 1_000L
        )

        assertNotNull(profile)
        assertEquals("fake-v1", profile!!.modelId)
        assertEquals(3, profile.sampleCount)
        assertEquals(1, store.loadAll().size)
        assertEquals(1.0, profile.embedding[0].toDouble(), 0.0001)
    }

    @Test
    fun `el enrolamiento exige varias muestras`() = runTest {
        val enrollment = SpeakerEnrollment(extractor, store, minSamples = 3)

        val profile = enrollment.enroll(
            profileId = "owner",
            displayName = "Ana",
            samples = listOf(audio(1), audio(1)),
            nowMillis = 1_000L
        )

        assertNull(profile)
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `la voz enrolada del dueno se identifica como OWNER`() = runTest {
        SpeakerEnrollment(extractor, store, minSamples = 3).enroll(
            profileId = "owner",
            displayName = "Ana",
            samples = listOf(audio(1), audio(1), audio(1)),
            nowMillis = 1_000L
        )

        val identity = provider().identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.OWNER, identity.relation)
        assertEquals("Ana", identity.displayName)
        assertEquals(1.0, identity.confidence, 0.0001)
    }

    @Test
    fun `una voz distinta no se atribuye al dueno`() = runTest {
        SpeakerEnrollment(extractor, store, minSamples = 3).enroll(
            profileId = "owner",
            displayName = "Ana",
            samples = listOf(audio(1), audio(1), audio(1)),
            nowMillis = 1_000L
        )

        val identity = provider().identify(audio(2), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNKNOWN, identity.relation)
        assertNull(identity.profileId)
    }

    @Test
    fun `un audio corto queda incierto aunque la voz coincida`() = runTest {
        enrollOwner()

        // 1 s: por debajo del mínimo de 1,2 s de la política.
        val identity = provider().identify(audio(1, size = 32_044), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNCERTAIN, identity.relation)
    }

    @Test
    fun `un riesgo de spoofing alto degrada el turno aunque la voz coincida`() = runTest {
        enrollOwner()

        val identity = provider(
            spoofingDetector = FixedSpoofingDetector(SpoofingAssessment(0.92, "spoof-v1"))
        ).identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNCERTAIN, identity.relation)
        assertNull(identity.profileId)
    }

    @Test
    fun `sin modelo de spoofing la voz del dueno sigue siendo OWNER`() = runTest {
        enrollOwner()

        val identity = provider().identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.OWNER, identity.relation)
        assertEquals("Ana", identity.displayName)
    }

    @Test
    fun `un detector de spoofing que falla no bloquea el turno`() = runTest {
        enrollOwner()
        val failing = object : SpoofingDetector {
            override val modelId: String = "spoof-v1"
            override suspend fun assess(wavData: ByteArray): SpoofingAssessment? =
                throw IllegalStateException("modelo de spoofing no cargado")
        }

        val identity = provider(spoofingDetector = failing)
            .identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.OWNER, identity.relation)
    }

    @Test
    fun `un detector de spoofing lento no alarga el turno`() = runTest {
        enrollOwner()
        val slow = object : SpoofingDetector {
            override val modelId: String = "spoof-v1"
            override suspend fun assess(wavData: ByteArray): SpoofingAssessment {
                delay(5_000)
                return SpoofingAssessment(0.99, "spoof-v1")
            }
        }
        val provider = EmbeddingSpeakerIdentityProvider(
            extractor = extractor,
            store = store,
            spoofingDetector = slow,
            ownerProfileIdProvider = { "owner" },
            spoofTimeoutMillis = 100L
        )

        val identity = provider.identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.OWNER, identity.relation)
    }

    @Test
    fun `sin perfiles no se consulta el detector de spoofing`() = runTest {
        var calls = 0
        val counting = object : SpoofingDetector {
            override val modelId: String = "spoof-v1"
            override suspend fun assess(wavData: ByteArray): SpoofingAssessment {
                calls++
                return SpoofingAssessment(0.99, "spoof-v1")
            }
        }

        val identity = provider(spoofingDetector = counting)
            .identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNAVAILABLE, identity.relation)
        assertEquals(0, calls)
    }

    @Test
    fun `sin perfiles no hay identidad`() = runTest {
        val identity = provider().identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNAVAILABLE, identity.relation)
        assertEquals("fake-v1", identity.modelId)
    }

    @Test
    fun `los perfiles de otro modelo se ignoran`() = runTest {
        store.upsert(
            VoiceProfile(
                id = "owner",
                displayName = "Ana",
                embedding = ownerVector,
                modelId = "otro-modelo",
                enrolledAtMillis = 1_000L,
                sampleCount = 3
            )
        )

        val identity = provider().identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNAVAILABLE, identity.relation)
    }

    @Test
    fun `un extractor que falla no rompe la identificacion`() = runTest {
        val provider = EmbeddingSpeakerIdentityProvider(
            extractor = object : SpeakerEmbeddingExtractor {
                override val modelId: String = "fake-v1"
                override suspend fun embed(wavData: ByteArray): FloatArray? =
                    throw IllegalStateException("modelo no cargado")
            },
            store = store,
            ownerProfileIdProvider = { "owner" }
        )
        store.upsert(
            VoiceProfile(
                id = "owner",
                displayName = "Ana",
                embedding = ownerVector,
                modelId = "fake-v1",
                enrolledAtMillis = 1_000L,
                sampleCount = 3
            )
        )

        val identity = provider.identify(audio(1), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNAVAILABLE, identity.relation)
    }

    @Test
    fun `el borrado elimina el perfil y su evidencia`() = runTest {
        SpeakerEnrollment(extractor, store, minSamples = 3).enroll(
            profileId = "owner",
            displayName = "Ana",
            samples = listOf(audio(1), audio(1), audio(1)),
            nowMillis = 1_000L
        )

        store.delete("owner")

        assertTrue(store.loadAll().isEmpty())
        assertEquals(
            SpeakerRelation.UNAVAILABLE,
            provider().identify(audio(1), nowMillis = 2_000L).relation
        )
    }

    @Test
    fun `el detector noop no inventa un riesgo de spoofing`() = runTest {
        assertNull(NoopSpoofingDetector.assess(audio(1)))
    }

    @Test
    fun `la evidencia de spoofing valida rango y modelo`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpoofingAssessment(risk = 1.2, modelId = "spoof-v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpoofingAssessment(risk = 0.2, modelId = " ")
        }
    }

    private class ScriptedExtractor(
        override val modelId: String,
        private val embeddings: Map<Int, FloatArray>
    ) : SpeakerEmbeddingExtractor {
        override suspend fun embed(wavData: ByteArray): FloatArray? {
            if (wavData.isEmpty()) return null
            return embeddings[wavData.first().toInt() and 0xFF]
        }
    }

    private class InMemoryVoiceProfileStore : VoiceProfileStore {
        private val profiles = mutableListOf<VoiceProfile>()

        override suspend fun loadAll(): List<VoiceProfile> = profiles.toList()

        override suspend fun upsert(profile: VoiceProfile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override suspend fun delete(profileId: String) {
            profiles.removeAll { it.id == profileId }
        }

        override suspend fun clear() {
            profiles.clear()
        }
    }
}
