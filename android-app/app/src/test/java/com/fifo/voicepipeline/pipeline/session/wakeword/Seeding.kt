package com.fifo.voicepipeline.pipeline.session.wakeword

/**
 * Semilla estable derivada de un identificador (ADR-0044).
 *
 * No se usa `String.hashCode`: su valor está especificado, pero cualquier
 * refactor de nombres cambiaría el corpus sin que nadie lo note. Aquí el
 * identificador *es* la semilla, y el audio de cada caso queda atado a su
 * nombre para siempre.
 */
internal object Seeding {

    /** FNV-1a de 64 bits: estable entre ejecuciones y entre JVM. */
    fun of(vararg parts: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (part in parts) {
            for (character in part) {
                hash = hash xor character.code.toLong()
                hash *= 0x100000001b3L
            }
            hash = hash xor 0x2fL
        }
        return hash
    }
}
