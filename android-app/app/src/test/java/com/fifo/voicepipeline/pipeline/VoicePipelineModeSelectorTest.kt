package com.fifo.voicepipeline.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePipelineModeSelectorTest {

    @Test
    fun `debug flag off keeps legacy even with cloud stt`() {
        assertEquals(
            VoicePipelineMode.LEGACY,
            VoicePipelineModeSelector.select(flagEnabled = false, hasCloudStt = true)
        )
    }

    @Test
    fun `release flag on selects session when cloud stt exists`() {
        assertEquals(
            VoicePipelineMode.SESSION,
            VoicePipelineModeSelector.select(flagEnabled = true, hasCloudStt = true)
        )
    }

    @Test
    fun `release flag on without cloud stt falls back to legacy`() {
        assertEquals(
            VoicePipelineMode.LEGACY,
            VoicePipelineModeSelector.select(flagEnabled = true, hasCloudStt = false)
        )
    }
}
