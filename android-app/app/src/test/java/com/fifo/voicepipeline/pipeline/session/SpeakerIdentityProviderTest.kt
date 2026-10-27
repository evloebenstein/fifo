package com.fifo.voicepipeline.pipeline.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        ownerProfileId: String? = "owner"
    ) = EmbeddingSpeakerIdentityProvider(
        extractor = extractor,
        store = store,
        ownerProfileIdProvider = { ownerProfileId },
        config = config
    )

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
        SpeakerEnrollment(extractor, store, minSamples = 3).enroll(
            profileId = "owner",
            displayName = "Ana",
            samples = listOf(audio(1), audio(1), audio(1)),
            nowMillis = 1_000L
        )

        // 1 s: por debajo del mínimo de 1,2 s de la política.
        val identity = provider().identify(audio(1, size = 32_044), nowMillis = 2_000L)

        assertEquals(SpeakerRelation.UNCERTAIN, identity.relation)
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
