package com.fifo.voicepipeline.pipeline.session.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Exporta el corpus sintético de locutores a WAV + manifiesto JSONL (ADR-0048).
 *
 * Es el mismo contrato que ADR-0047 para el wake word: audio mono a 16 kHz,
 * PCM de 16 bits y una línea JSON por clip con split, locutor, rol, condición y
 * duración. Con eso un script externo puede extraer embeddings con un modelo
 * real (ReDimNet/ECAPA/Eagle) y evaluar el umbral sin depender de la JVM.
 *
 * Está apagada por defecto. Se activa con:
 *
 * ```
 * $env:FIFO_SPEAKER_EXPORT_DIR="C:\ruta\al\corpus"
 * .\gradlew.bat testDebugUnitTest --tests "*SpeakerCorpusExportTest"
 * ```
 *
 * No se commitea ningún WAV. Los locutores de `calibration` son disjuntos de
 * los de `evaluation`: el umbral se fija con unos y se reporta con otros.
 */
class SpeakerCorpusExportTest {

    private data class Row(val audio: String, val json: String)

    @Test
    fun `exporta el corpus de locutores a wav y manifiesto`() {
        val target = System.getenv("FIFO_SPEAKER_EXPORT_DIR")
        assumeTrue(
            "exportación offline; define FIFO_SPEAKER_EXPORT_DIR para ejecutarla",
            !target.isNullOrBlank()
        )
        val root = File(target!!).absoluteFile
        require(!root.exists() || root.isDirectory) { "la ruta de exportación no es un directorio: $root" }
        root.mkdirs()

        val rows = buildList {
            addEnrollment(
                split = "enroll",
                utterances = SpeakerVerificationCorpus.enrollment(),
                role = "enrolled"
            )
            addEnrollment(
                split = "enroll",
                utterances = SpeakerVerificationCorpus.calibrationEnrollment(),
                role = "enrolled"
            )
            addCases(
                split = "calibration",
                utterances = SpeakerVerificationCorpus.calibrationTests(),
                enrolledNames = SpeakerVerificationCorpus.CALIBRATION_ENROLLED.map { it.name }.toSet()
            )
            addCases(
                split = "evaluation",
                utterances = SpeakerVerificationCorpus.tests(),
                enrolledNames = SpeakerVerificationCorpus.ENROLLED.map { it.name }.toSet()
            )
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
        val bySpeaker = rows.map { row ->
            Regex("\"speaker\":\"([^\"]+)\"").find(row.json)?.groupValues?.get(1) ?: "?"
        }.toSet()
        println(
            "Corpus de locutores exportado a ${root.path}: ${rows.size} clips, " +
                "${bySpeaker.size} locutores, " +
                bySplit.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
        )
        System.out.flush()

        assertTrue("el manifiesto debe tener una línea por clip", manifest.readLines().size == rows.size)
        assertTrue(
            "calibración y evaluación deben ser disjuntas",
            SpeakerVerificationCorpus.CALIBRATION_SPEAKERS.map { it.name }.toSet()
                .intersect(SpeakerVerificationCorpus.SPEAKERS.map { it.name }.toSet())
                .isEmpty()
        )
    }

    private fun MutableList<Row>.addEnrollment(
        split: String,
        utterances: Map<String, List<SpeakerUtterance>>,
        role: String
    ) {
        for ((speakerName, clips) in utterances) {
            for (clip in clips) {
                val relative = "enroll/${clip.id}.wav"
                writeWav(File(EXPORT_ROOT, relative), clip.pcm)
                add(
                    Row(
                        audio = relative,
                        json = jsonLine(
                            id = clip.id,
                            audio = relative,
                            split = split,
                            speaker = speakerName,
                            role = role,
                            condition = clip.condition,
                            syllables = clip.syllables,
                            durationMillis = clip.durationMillis
                        )
                    )
                )
            }
        }
    }

    private fun MutableList<Row>.addCases(
        split: String,
        utterances: List<SpeakerUtterance>,
        enrolledNames: Set<String>
    ) {
        for (clip in utterances) {
            val relative = "$split/${clip.id}.wav"
            writeWav(File(EXPORT_ROOT, relative), clip.pcm)
            add(
                Row(
                    audio = relative,
                    json = jsonLine(
                        id = clip.id,
                        audio = relative,
                        split = split,
                        speaker = clip.speaker.name,
                        role = if (clip.speaker.name in enrolledNames) "enrolled" else "unseen",
                        condition = clip.condition,
                        syllables = clip.syllables,
                        durationMillis = clip.durationMillis
                    )
                )
            )
        }
    }

    private fun jsonLine(
        id: String,
        audio: String,
        split: String,
        speaker: String,
        role: String,
        condition: SpeakerCondition,
        syllables: Int,
        durationMillis: Long
    ): String = buildString {
        append('{')
        appendField("id", id)
        append(',')
        appendField("audio", audio)
        append(',')
        appendField("split", split)
        append(',')
        appendField("speaker", speaker)
        append(',')
        appendField("role", role)
        append(",\"enrolled\":").append(role == "enrolled")
        append(',')
        appendField("condition", condition.id)
        append(",\"snr_db\":").append(condition.snrDb ?: "null")
        append(',')
        appendField("noise", condition.noise.name.lowercase(Locale.ROOT))
        append(',')
        appendField("room", condition.room?.id)
        append(",\"lombard\":").append(condition.lombard)
        append(",\"syllables\":").append(syllables)
        append(",\"duration_ms\":").append(durationMillis)
        append(",\"sample_rate\":").append(SPEAKER_SYNTH_SAMPLE_RATE)
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
        out.writeIntLe(SPEAKER_SYNTH_SAMPLE_RATE)
        out.writeIntLe(SPEAKER_SYNTH_SAMPLE_RATE * 2)
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
        /** Lo fija `init` con `FIFO_SPEAKER_EXPORT_DIR`; ver la documentación de la clase. */
        lateinit var EXPORT_ROOT: File
    }

    init {
        // JUnit crea una instancia por prueba; el destino se fija aquí para que
        // las funciones auxiliares no tengan que pasarlo por parámetro.
        val target = System.getenv("FIFO_SPEAKER_EXPORT_DIR")
        if (!target.isNullOrBlank()) EXPORT_ROOT = File(target).absoluteFile
    }
}
