package com.fifo.voicepipeline.audio

/**
 * Utilidad para sanitizar texto antes de que sea enviado al sintetizador de voz (TTS)
 * o mostrado en la interfaz.
 *
 * Elimina:
 * 1. Emojis (😊, 👋, ❤️, etc.) que los sintetizadores leen como "cara sonriente", "mano saludando", etc.
 * 2. Asteriscos Markdown (* y **) que los sintetizadores leen literalmente como "asterisco asterisco".
 * 3. Acotaciones teatrales entre asteriscos o corchetes (ej: *sonríe*, *pausa*, [ríe]).
 * 4. Prefijos de diálogo de LLM (ej: "Fifo:", "**Fifo:**", "Agente:").
 * 5. Títulos Markdown (#), viñetas (- o *), y marcas de formato.
 */
object TextSanitizer {

    // Expresión regular exhaustiva para capturar todos los emojis, símbolos pictográficos,
    // pares subrogados, selectores de variación y uniones zero-width
    private val EMOJI_REGEX = Regex(
        "[\\p{So}\\p{Cn}\\uFE00-\\uFE0F\\u200D\\u20E3\\u2600-\\u27BF]|[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]"
    )

    fun cleanForSpeech(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw

        // 1. Eliminar emojis y pictogramas para que el sintetizador no diga "cara feliz", etc.
        text = text.replace(EMOJI_REGEX, " ")

        // 2. Eliminar etiquetas HTML o XML si hubiera (<...>)
        text = text.replace(Regex("<[^>]*>"), " ")

        // 3. Eliminar bloques de código markdown
        text = text.replace(Regex("```[\\s\\S]*?```"), " ")
        text = text.replace(Regex("`([^`]+)`"), "$1")

        // 4. Eliminar acotaciones teatrales o escénicas entre asteriscos o corchetes:
        // Ej: *sonríe*, *pausa*, *con voz cálida*, [sonrisa], (pausa reflexiva)
        text = text.replace(Regex("\\*[^*]+\\*"), " ")
        text = text.replace(Regex("\\[[^\\]]+\\]"), " ")
        text = text.replace(Regex("\\((?:pausa|sonríe|suspira|risas?|tono\\s+cálido|con\\s+cariño)[^)]*\\)", RegexOption.IGNORE_CASE), " ")

        // 5. Eliminar prefijos de rol de diálogo:
        // Ej: "Fifo:", "**Fifo:**", "Agente Fifo:", "Respuesta:", "Assistant:"
        text = text.replace(Regex("(?i)^[\\s*#]*\\b(?:fifo|agente fifo|asistente|assistant)\\b[\\s*#]*:?\\s*"), "")

        // 6. Eliminar marcas de formato Markdown (negrita, cursiva, subrayado)
        text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        text = text.replace(Regex("\\*([^*]+)\\*"), "$1")
        text = text.replace(Regex("__([^_]+)__"), "$1")
        text = text.replace(Regex("_([^_]+)_"), "$1")
        text = text.replace(Regex("~~([^~]+)~~"), "$1")

        // 7. Eliminar encabezados Markdown (#, ##, ###)
        text = text.replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")

        // 8. Eliminar viñetas de lista (- item, * item, + item, 1. item) dejando solo el texto
        text = text.replace(Regex("^[\\s]*[-*•+]\\s+", RegexOption.MULTILINE), "")
        text = text.replace(Regex("^[\\s]*\\d+\\.\\s+", RegexOption.MULTILINE), "")

        // 9. Enlaces Markdown [texto](url) -> texto
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")

        // 10. Eliminar cualquier asterisco o símbolo residual suelto (*, #, ~, _, `)
        // para asegurar que el motor TTS jamás pronuncie la palabra "asterisco"
        text = text.replace("*", "")
        text = text.replace("#", "")
        text = text.replace("~", "")
        text = text.replace("`", "")
        text = text.replace("_", "")

        // 11. Normalizar espacios y saltos de línea repetidos
        text = text.replace(Regex("\\s+"), " ").trim()

        return text
    }
}
