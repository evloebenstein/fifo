package com.fifo.voicepipeline.pipeline.session.speaker

import com.fifo.voicepipeline.pipeline.session.wakeword.LombardEffect
import com.fifo.voicepipeline.pipeline.session.wakeword.NoiseKind
import com.fifo.voicepipeline.pipeline.session.wakeword.RoomAcoustics
import com.fifo.voicepipeline.pipeline.session.wakeword.RoomProfile
import com.fifo.voicepipeline.pipeline.session.wakeword.Rooms
import com.fifo.voicepipeline.pipeline.session.wakeword.SYNTH_SAMPLE_RATE
import com.fifo.voicepipeline.pipeline.session.wakeword.SimulatedNoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Exporta un corpus de campo **simulado con voces humanas reales** (ADR-0048).
 *
 * El corpus sintético de `SpeakerVerificationHarness` usa voz de formantes y los
 * modelos reales no la reconocen. Este test toma un pool de voces humanas
 * (`prepare_librispeech_pool.py`) y le aplica **la misma** acústica del arnés:
 * `RoomAcoustics`, `SimulatedNoise` y `LombardEffect`. No hay un segundo
 * simulador que pueda divergir del primero.
 *
 * Reparto: los primeros locutores son calibración y el resto evaluación; dentro
 * de cada split, la mitad son `enrolled` y la mitad `unseen`. Los enrolled
 * aportan 3 frases de enrolamiento y 4 de uso; los unseen solo 4 de uso. Las 4
 * frases de uso se aumentan con seis condiciones (limpio, sala, rosa, blanco,
 * balbuceo y Lombard), así que no hacen falta 24 grabaciones por persona.
 * `FIFO_REAL_SPEECH_CALIBRATION` permite cambiar el tamaño de la cohorte de
 * calibración para medir si AS-Norm se estabiliza con más perfiles.
 *
 * `FIFO_REAL_SPEECH_CHANNEL=telephony` añade `TelephonyChannel` al final de la
 * cadena: banda, coloración y AGC **por clip de uso**, con una sola sesión de
 * canal para el enrolamiento. Rompe el atajo de "mismo micrófono por locutor"
 * que el corpus de una sola charla regalaba. Es una estimación dura, no campo:
 * el canal original de la grabación no se puede quitar.
 *
 * Está apagado por defecto. Se activa con:
 *
 * ```
 * $env:FIFO_REAL_SPEECH_POOL="C:\ruta\al\pool"
 * $env:FIFO_REAL_SPEECH_EXPORT_DIR="C:\ruta\al\corpus"
 * $env:FIFO_REAL_SPEECH_CHANNEL="telephony"
 * .\gradlew.bat testDebugUnitTest --tests "*RealSpeechAugmentationExportTest"
 * ```
 *
 * No se commitea ningún WAV: el corpus se regenera desde LibriSpeech y el test
 * no forma parte de la suite normal. Sigue siendo lectura limpia aumentada, no
 * una grabación de campo: sirve para regresión y para estudiar la simulación,
 * no para autorizar un detector.
 */
class RealSpeechAugmentationExportTest {

    private data class Condition(
        val id: String,
        val snrDb: Int? = null,
        val noise: NoiseKind = NoiseKind.WHITE,
        val room: RoomProfile? = null,
        val lombard: Boolean = false
    )

    private data class Row(val audio: String, val json: String)

    private data class SplitSpeaker(
        val name: String,
        val split: String,
        val role: String,
        val directory: File
    )

    @Test
    fun `exporta un corpus de campo simulado desde voces reales`() {
        val poolPath = System.getenv("FIFO_REAL_SPEECH_POOL")
        val targetPath = System.getenv("FIFO_REAL_SPEECH_EXPORT_DIR")
        assumeTrue(
            "exportación offline; define FIFO_REAL_SPEECH_POOL y FIFO_REAL_SPEECH_EXPORT_DIR",
            !poolPath.isNullOrBlank() && !targetPath.isNullOrBlank()
        )

        val pool = File(poolPath!!).absoluteFile
        require(pool.isDirectory) { "no existe el pool de voces: $pool" }
        val speakers = pool.listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name }
            ?.filter { directory -> wavFiles(directory).size >= 7 }
            ?: emptyList()
        require(speakers.size >= 8) {
            "hacen falta al menos 8 locutores con 7 WAV cada uno; hay ${speakers.size}"
        }

        val requestedCalibration = System.getenv("FIFO_REAL_SPEECH_CALIBRATION")?.toIntOrNull()
        val calibrationCount = requestedCalibration
            ?.coerceIn(4, speakers.size - 4)
            ?: (speakers.size / 3).coerceIn(4, 12)
        val calibration = speakers.take(calibrationCount)
        val evaluation = speakers.drop(calibrationCount)
        require(evaluation.size >= 4) {
            "hacen falta al menos 4 locutores de evaluación; hay ${evaluation.size}"
        }

        val channelMode = System.getenv("FIFO_REAL_SPEECH_CHANNEL")?.lowercase(Locale.ROOT) ?: "none"
        require(channelMode == "none" || channelMode == "telephony") {
            "FIFO_REAL_SPEECH_CHANNEL debe ser none o telephony; es $channelMode"
        }
        val telephony = channelMode == "telephony"

        val root = File(targetPath!!).absoluteFile
        require(!root.exists() || root.isDirectory) { "la ruta de exportación no es un directorio: $root" }
        root.mkdirs()

        val rows = mutableListOf<Row>()
        var augmented = 0

        for ((split, members) in listOf("calibration" to calibration, "evaluation" to evaluation)) {
            val half = members.size / 2
            val splitSpeakers = members.mapIndexed { index, directory ->
                SplitSpeaker(
                    name = "real-${directory.name}",
                    split = split,
                    role = if (index < half) "enrolled" else "unseen",
                    directory = directory
                )
            }

            for (speaker in splitSpeakers.filter { it.role == "enrolled" }) {
                val clips = wavFiles(speaker.directory).take(7)
                val enrollChannel = if (telephony) {
                    TelephonyChannel.profile("${speaker.name}-enroll-session".hashCode())
                } else {
                    null
                }
                for ((index, clip) in clips.take(3).withIndex()) {
                    val relative = "enroll/${speaker.name}-enroll-$index.wav"
                    var pcm = normalise(trim(readWav(clip)))
                    if (enrollChannel != null) pcm = TelephonyChannel.apply(pcm, enrollChannel)
                    writeWav(File(root, relative), pcm)
                    rows += row(
                        speaker = speaker,
                        split = "enroll",
                        condition = CONDITIONS.first(),
                        index = index,
                        relative = relative,
                        source = clip.name,
                        durationMs = pcm.size * 1000L / SYNTH_SAMPLE_RATE,
                        channel = enrollChannel
                    )
                }
                for ((index, clip) in clips.drop(3).take(4).withIndex()) {
                    augmented += addConditions(root, rows, speaker, clip, index, telephony)
                }
            }
            for (speaker in splitSpeakers.filter { it.role == "unseen" }) {
                val clips = wavFiles(speaker.directory).take(4)
                for ((index, clip) in clips.withIndex()) {
                    augmented += addConditions(root, rows, speaker, clip, index, telephony)
                }
            }
        }

        require(augmented > 0) { "no se generó ningún clip aumentado" }
        val manifest = File(root, "manifest.jsonl")
        manifest.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (row in rows) writer.appendLine(row.json)
        }

        val bySplit = rows.groupingBy { row ->
            Regex("\"split\":\"([^\"]+)\"").find(row.json)?.groupValues?.get(1) ?: "?"
        }.eachCount()
        val speakersInCalibration = rows.mapNotNull { row ->
            if (row.json.contains("\"split\":\"calibration\"")) {
                Regex("\"speaker\":\"([^\"]+)\"").find(row.json)?.groupValues?.get(1)
            } else {
                null
            }
        }.toSet()
        val speakersInEvaluation = rows.mapNotNull { row ->
            if (row.json.contains("\"split\":\"evaluation\"")) {
                Regex("\"speaker\":\"([^\"]+)\"").find(row.json)?.groupValues?.get(1)
            } else {
                null
            }
        }.toSet()

        println(
            "Corpus real aumentado en ${root.path}: ${rows.size} clips, " +
                "$augmented aumentados, ${speakers.size} locutores, canal=$channelMode, " +
                bySplit.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
        )
        println("calibración: ${speakersInCalibration.sorted()}")
        println("evaluación: ${speakersInEvaluation.sorted()}")
        System.out.flush()

        assertEquals("el manifiesto debe tener una línea por clip", rows.size, manifest.readLines().size)
        assertTrue("calibración y evaluación deben ser disjuntas", speakersInCalibration.intersect(speakersInEvaluation).isEmpty())
        assertTrue("deben existir locutores vistos y no vistos", speakersInCalibration.isNotEmpty() && speakersInEvaluation.isNotEmpty())
    }

    private fun addConditions(
        root: File,
        rows: MutableList<Row>,
        speaker: SplitSpeaker,
        clip: File,
        index: Int,
        telephony: Boolean
    ): Int {
        var written = 0
        for (condition in CONDITIONS) {
            val relative = "${speaker.split}/${speaker.name}-${condition.id}-$index.wav"
            val seed = "${speaker.name}-${condition.id}-$index".hashCode()
            var rendered = render(normalise(trim(readWav(clip))), condition, seed)
            val channel = if (telephony) {
                TelephonyChannel.profile("${speaker.name}-${condition.id}-$index-call".hashCode())
            } else {
                null
            }
            if (channel != null) rendered = TelephonyChannel.apply(rendered, channel)
            writeWav(File(root, relative), rendered)
            rows += row(
                speaker = speaker,
                split = speaker.split,
                condition = condition,
                index = index,
                relative = relative,
                source = clip.name,
                durationMs = rendered.size * 1000L / SYNTH_SAMPLE_RATE,
                channel = channel
            )
            written++
        }
        return written
    }

    private fun row(
        speaker: SplitSpeaker,
        split: String,
        condition: Condition,
        index: Int,
        relative: String,
        source: String,
        durationMs: Long,
        channel: ChannelProfile?
    ): Row {
        val id = if (split == "enroll") {
            "${speaker.name}-enroll-$index"
        } else {
            "${speaker.name}-${condition.id}-$index"
        }
        val json = buildString {
            append('{')
            appendField("id", id)
            append(',')
            appendField("audio", relative)
            append(',')
            appendField("split", split)
            append(',')
            appendField("speaker", speaker.name)
            append(',')
            appendField("role", speaker.role)
            append(",\"enrolled\":").append(speaker.role == "enrolled")
            append(',')
            appendField("condition", condition.id)
            append(",\"snr_db\":").append(condition.snrDb ?: "null")
            append(',')
            appendField("noise", condition.noise.name.lowercase(Locale.ROOT))
            append(',')
            appendField("room", condition.room?.id)
            append(",\"lombard\":").append(condition.lombard)
            append(",\"channel\":")
            if (channel == null) append("null") else append("\"telephony\"")
            append(',')
            appendField("channel_band", channel?.band?.id)
            append(",\"channel_tilt_db\":").append(channel?.tiltDb ?: "null")
            append(",\"channel_rms_dbfs\":").append(channel?.targetRmsDbfs ?: "null")
            append(",\"syllables\":null")
            append(",\"duration_ms\":").append(durationMs)
            append(",\"sample_rate\":").append(SYNTH_SAMPLE_RATE)
            append(',')
            appendField("source", source)
            append('}')
        }
        return Row(relative, json)
    }

    private fun render(pcm: ShortArray, condition: Condition, seed: Int): ShortArray {
        var signal = if (condition.lombard) LombardEffect.applyEffort(pcm) else pcm
        condition.room?.let { room ->
            signal = RoomAcoustics.reverberate(signal, room, Random(seed))
        }
        condition.snrDb?.let { snr ->
            val noise = SimulatedNoise.generate(
                kind = condition.noise,
                samples = signal.size,
                rng = Random(seed xor 0x5f3759df)
            )
            signal = SimulatedNoise.mixAtSnr(signal, noise, snr)
        }
        return signal
    }

    /** Recorta a 8 s: un turno de Fifo no dura 30 s y un clip largo no debe hacer fácil la tarea. */
    private fun trim(pcm: ShortArray): ShortArray =
        if (pcm.size <= MAX_SAMPLES) pcm else pcm.copyOf(MAX_SAMPLES)

    /** Nivela el pico para que el esfuerzo Lombard y la mezcla no recorten por accidente. */
    private fun normalise(pcm: ShortArray, targetPeak: Double = 0.7): ShortArray {
        var peak = 0.0
        for (sample in pcm) peak = maxOf(peak, abs(sample / 32768.0))
        if (peak <= 1e-6) return pcm
        val gain = targetPeak / peak
        return ShortArray(pcm.size) {
            (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun wavFiles(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** WAV PCM 16 bits, mono, little-endian, sin `javax.sound`. */
    private fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV demasiado corto: $file" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "no es RIFF: $file" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "no es WAVE: $file" }

        var offset = 12
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = leInt(bytes, offset + 4)
            val start = offset + 8
            require(size >= 0 && start + size <= bytes.size) { "chunk inválido en $file" }
            when (id) {
                "fmt " -> {
                    format = leShort(bytes, start)
                    channels = leShort(bytes, start + 2)
                    sampleRate = leInt(bytes, start + 4)
                    bits = leShort(bytes, start + 14)
                }
                "data" -> data = bytes.copyOfRange(start, start + size)
            }
            offset = start + size + (size and 1)
        }

        require(format == 1) { "$file: se esperaba PCM, formato $format" }
        require(channels == 1) { "$file: se esperaba mono, $channels canales" }
        require(sampleRate == SYNTH_SAMPLE_RATE) { "$file: se esperaban 16 kHz, $sampleRate Hz" }
        require(bits == 16) { "$file: se esperaban 16 bits, $bits" }
        val pcm = data ?: error("$file: falta el chunk data")
        require(pcm.size % 2 == 0) { "$file: datos PCM impares" }
        return ShortArray(pcm.size / 2) { index ->
            val low = pcm[index * 2].toInt() and 0xFF
            val high = pcm[index * 2 + 1].toInt() and 0xFF
            (low or (high shl 8)).toShort()
        }
    }

    /** WAV PCM 16 bits, mono, little-endian, sin dependencias de `javax.sound`. */
    private fun writeWav(file: File, pcm: ShortArray) {
        file.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(36 + dataSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(16)
        out.writeShortLe(1)
        out.writeShortLe(1)
        out.writeIntLe(SYNTH_SAMPLE_RATE)
        out.writeIntLe(SYNTH_SAMPLE_RATE * 2)
        out.writeShortLe(2)
        out.writeShortLe(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.writeIntLe(dataSize)
        for (sample in pcm) out.writeShortLe(sample.toInt() and 0xFFFF)
        file.writeBytes(out.toByteArray())
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

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

    private fun StringBuilder.appendField(name: String, value: String?) {
        append('"').append(name).append("\":")
        if (value == null) {
            append("null")
        } else {
            append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        }
    }

    private companion object {
        /** 8 s a 16 kHz: el turno típico de Fifo, no una locución de LibriSpeech. */
        const val MAX_SAMPLES = 8 * SYNTH_SAMPLE_RATE

        val CONDITIONS = listOf(
            Condition("clean"),
            Condition("living", room = Rooms.LIVING_ROOM),
            Condition("pink-10", snrDb = 10, noise = NoiseKind.PINK),
            Condition("white-5", snrDb = 5, noise = NoiseKind.WHITE),
            Condition("babble-15", snrDb = 15, noise = NoiseKind.BABBLE, room = Rooms.LIVING_ROOM),
            Condition(
                "living-lombard-pink-20",
                snrDb = 20,
                noise = NoiseKind.PINK,
                room = Rooms.LIVING_ROOM,
                lombard = true
            )
        )
    }
}