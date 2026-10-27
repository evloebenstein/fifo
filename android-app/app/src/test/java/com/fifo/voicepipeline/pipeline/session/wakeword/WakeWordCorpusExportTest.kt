package com.fifo.voicepipeline.pipeline.session.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Exporta el corpus sintético a WAV + manifiesto JSONL (ADR-0047).
 *
 * El arnés de ADR-0044/0045 mide en la JVM, pero entrenar un modelo con
 * embeddings no cabe en una prueba: necesita un pipeline de Python, muchas
 * voces y varios epochs. Esta exportación es el contrato entre ambos mundos:
 * WAV mono a 16 kHz, PCM de 16 bits, y una línea JSON por clip con la etiqueta,
 * el split, el locutor y la condición.
 *
 * Está apagada por defecto. Se activa con:
 *
 * ```
 * $env:FIFO_WAKEWORD_EXPORT_DIR="C:\ruta\al\corpus"
 * .\gradlew.bat testDebugUnitTest --tests "*WakeWordCorpusExportTest"
 * ```
 *
 * No se commitea ningún WAV: el corpus se regenera de forma determinista y el
 * manifiesto es la única fuente de verdad del experimento.
 */
class WakeWordCorpusExportTest {

    private data class Row(val audio: String, val json: String)

    @Test
    fun `exporta el corpus a wav y manifiesto`() {
        val target = System.getenv("FIFO_WAKEWORD_EXPORT_DIR")
        assumeTrue(
            "exportación offline; define FIFO_WAKEWORD_EXPORT_DIR para ejecutarla",
            !target.isNullOrBlank()
        )
        val root = File(target!!).absoluteFile
        require(!root.exists() || root.isDirectory) { "la ruta de exportación no es un directorio: $root" }
        root.mkdirs()

        val rows = buildList {
            addTrainingClips("train", WakeWordTrainingCorpus.trainingClips())
            addTrainingClips("validation", WakeWordTrainingCorpus.validationClips())
            addCases("base", WakeWordCorpus.positives(), "positive")
            addCases("base", WakeWordCorpus.hardNegatives(), "hard_negative")
            addCases("base", WakeWordCorpus.conversations(), "conversation")
            addCases("field", WakeWordCorpus.fieldPositives(), "positive")
            addCases("field", WakeWordCorpus.fieldHardNegatives(), "hard_negative")
            addCases("field", WakeWordCorpus.fieldConversations(), "conversation")
            addEnrollment()
        }

        val manifest = File(root, "manifest.jsonl")
        manifest.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (row in rows) writer.appendLine(row.json)
        }

        val missing = rows.count { !File(root, it.audio).isFile }
        assertEquals("todos los clips deben existir en disco", 0, missing)

        val bySplit = rows.groupingBy { row ->
            Regex("\"split\":\"([^\"]+)\"").find(row.json)?.groupValues?.get(1) ?: "?"
        }.eachCount()
        println(
            "Corpus exportado a ${root.path}: ${rows.size} clips " +
                bySplit.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
        )
        System.out.flush()

        assertTrue("el manifiesto debe tener una línea por clip", manifest.readLines().size == rows.size)
    }

    private fun MutableList<Row>.addTrainingClips(split: String, clips: List<TrainingClip>) {
        for (clip in clips) {
            val relative = "$split/${clip.id}.wav"
            writeWav(File(EXPORT_ROOT, relative), clip.pcm)
            add(
                Row(
                    audio = relative,
                    json = jsonLine(
                        id = clip.id,
                        audio = relative,
                        split = split,
                        family = clip.family,
                        label = if (clip.positive) 1 else 0,
                        speaker = clip.speakerName,
                        enrolled = clip.speakerName in WakeWordCorpus.ENROLLED.map { it.name },
                        condition = clip.condition.label,
                        snrDb = clip.condition.snrDb,
                        noise = clip.condition.noise.name.lowercase(Locale.ROOT),
                        room = clip.condition.room?.id,
                        lombard = clip.condition.lombard,
                        durationMs = clip.pcm.size * 1000L / SYNTH_SAMPLE_RATE,
                        wordStartMs = clip.wordStartSample?.times(1000L)?.div(SYNTH_SAMPLE_RATE),
                        wordEndMs = clip.wordEndSample?.times(1000L)?.div(SYNTH_SAMPLE_RATE)
                    )
                )
            )
        }
    }

    private fun MutableList<Row>.addCases(split: String, cases: List<WakeWordCase>, family: String) {
        for (case in cases) {
            val relative = "$split/${case.id}.wav"
            writeWav(File(EXPORT_ROOT, relative), case.pcm)
            add(
                Row(
                    audio = relative,
                    json = jsonLine(
                        id = case.id,
                        audio = relative,
                        split = split,
                        family = family,
                        label = if (family == "positive") 1 else 0,
                        speaker = case.speaker.name,
                        enrolled = case.enrolled,
                        condition = case.condition.label,
                        snrDb = case.condition.snrDb,
                        noise = case.condition.noise.name.lowercase(Locale.ROOT),
                        room = case.condition.room?.id,
                        lombard = case.condition.lombard,
                        durationMs = case.durationMs,
                        wordStartMs = case.wordStartMs,
                        wordEndMs = case.wordEndMs
                    )
                )
            )
        }
    }

    private fun MutableList<Row>.addEnrollment() {
        for (speaker in WakeWordCorpus.ENROLLED) {
            val id = WakeWordCorpus.enrollmentClipId(speaker)
            val relative = "enroll/$id.wav"
            val pcm = WakeWordCorpus.enrollmentClip(speaker)
            writeWav(File(EXPORT_ROOT, relative), pcm)
            val bounds = WakeWordCorpus.enrollmentWordBounds(speaker)
            add(
                Row(
                    audio = relative,
                    json = jsonLine(
                        id = id,
                        audio = relative,
                        split = "enroll",
                        family = "positive",
                        label = 1,
                        speaker = speaker.name,
                        enrolled = true,
                        condition = "clean",
                        snrDb = null,
                        noise = "white",
                        room = null,
                        lombard = false,
                        durationMs = pcm.size * 1000L / SYNTH_SAMPLE_RATE,
                        wordStartMs = bounds.first * 1000L / SYNTH_SAMPLE_RATE,
                        wordEndMs = bounds.last * 1000L / SYNTH_SAMPLE_RATE
                    )
                )
            )
        }
    }

    private fun jsonLine(
        id: String,
        audio: String,
        split: String,
        family: String,
        label: Int,
        speaker: String,
        enrolled: Boolean,
        condition: String,
        snrDb: Int?,
        noise: String,
        room: String?,
        lombard: Boolean,
        durationMs: Long,
        wordStartMs: Long?,
        wordEndMs: Long?
    ): String = buildString {
        append('{')
        appendField("id", id)
        append(',')
        appendField("audio", audio)
        append(',')
        appendField("split", split)
        append(',')
        appendField("family", family)
        append(",\"label\":").append(label)
        append(',')
        appendField("speaker", speaker)
        append(",\"enrolled\":").append(enrolled)
        append(',')
        appendField("condition", condition)
        append(",\"snr_db\":").append(snrDb ?: "null")
        append(',')
        appendField("noise", noise)
        append(',')
        appendField("room", room)
        append(",\"lombard\":").append(lombard)
        append(",\"duration_ms\":").append(durationMs)
        append(",\"word_start_ms\":").append(wordStartMs ?: "null")
        append(",\"word_end_ms\":").append(wordEndMs ?: "null")
        append(",\"sample_rate\":").append(SYNTH_SAMPLE_RATE)
        append('}')
    }

    private fun StringBuilder.appendField(name: String, value: String?) {
        append('"').append(name).append("\":")
        if (value == null) {
            append("null")
        } else {
            append('"')
            append(value.replace("\\", "\\\\").replace("\"", "\\\""))
            append('"')
        }
    }

    /** WAV PCM 16 bits, mono, little-endian. Sin dependencias de `javax.sound`. */
    private fun writeWav(file: File, pcm: ShortArray) {
        file.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(36 + dataSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(16)
        out.writeShortLe(1) // PCM
        out.writeShortLe(1) // mono
        out.writeIntLe(SYNTH_SAMPLE_RATE)
        out.writeIntLe(SYNTH_SAMPLE_RATE * 2)
        out.writeShortLe(2) // block align
        out.writeShortLe(16) // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(dataSize)
        for (sample in pcm) out.writeShortLe(sample.toInt() and 0xFFFF)
        file.writeBytes(out.toByteArray())
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private companion object {
        /** Lo fija `init` con `FIFO_WAKEWORD_EXPORT_DIR`; ver la documentación de la clase. */
        lateinit var EXPORT_ROOT: File
    }

    init {
        // JUnit crea una instancia por prueba; el destino se fija aquí para que
        // las funciones auxiliares no tengan que pasarlo por parámetro.
        val target = System.getenv("FIFO_WAKEWORD_EXPORT_DIR")
        if (!target.isNullOrBlank()) EXPORT_ROOT = File(target).absoluteFile
    }
}
