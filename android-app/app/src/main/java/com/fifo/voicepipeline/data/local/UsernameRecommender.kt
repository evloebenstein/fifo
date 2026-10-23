package com.fifo.voicepipeline.data.local

import com.fifo.voicepipeline.data.FifoContentFilter
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * Generador puro de usernames disponibles.
 *
 * No conoce SQLite ni Android. El DAO le inyecta la consulta de unicidad
 * (`isTaken`) y el año actual para que la política sea testeable en JVM.
 */
internal object UsernameRecommender {

    fun recommend(
        fullName: String,
        email: String?,
        batchIndex: Int,
        isTaken: (String) -> Boolean,
        currentYear: Int = Calendar.getInstance().get(Calendar.YEAR) % 100
    ): List<String> {
        val cleanName = Normalizer.normalize(fullName.trim(), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.ROOT)
        val nameTokens = cleanName.split(Regex("[\\s._-]+")).filter { it.isNotBlank() && it.length > 1 }
        val first = nameTokens.firstOrNull()?.filter { it.isLetterOrDigit() }?.take(18) ?: ""
        val lastTokens = nameTokens.drop(1).map { it.filter { ch -> ch.isLetterOrDigit() } }.filter { it.isNotBlank() }
        val last = lastTokens.firstOrNull()?.take(18) ?: ""

        // Extraer prefijo y tokens del correo
        val emailPrefix = email?.substringBefore("@")?.lowercase(Locale.ROOT)?.filter { it.isLetterOrDigit() || it == '.' || it == '_' } ?: ""
        val emailCleanAlpha = emailPrefix.filter { it.isLetterOrDigit() }
        val emailTokens = emailPrefix.split(Regex("[._]+")).filter { it.isNotBlank() && it.length > 1 }

        // Token secundario (apellido real, o segundo token del correo)
        val secondaryToken = when {
            last.isNotBlank() -> last
            emailTokens.size > 1 -> {
                val candidateToken = emailTokens.firstOrNull { eToken ->
                    !first.contains(eToken) && !eToken.contains(first)
                } ?: ""
                candidateToken.take(16)
            }
            else -> ""
        }

        val baseName = if (first.isNotBlank() && !first.equals("usuario", ignoreCase = true)) {
            first
        } else if (emailTokens.isNotEmpty()) {
            emailTokens.first().take(18)
        } else {
            "amigo"
        }

        // 5 BUCKETS ESTILÍSTICOS INDEPENDIENTES (Garantizan máxima diversidad estética sin repetición)

        // Bucket 1: Clásico / Canónico Completo
        val bucketCanonical = mutableListOf<String>()
        if (secondaryToken.isNotBlank()) {
            bucketCanonical.add("${baseName}_${secondaryToken}")
            bucketCanonical.add("${baseName}.${secondaryToken}")
            if (baseName.length + secondaryToken.length <= 18) {
                bucketCanonical.add("${baseName}${secondaryToken}")
            }
        } else {
            bucketCanonical.add(baseName)
            bucketCanonical.add("${baseName}_cl")
            bucketCanonical.add("${baseName}_dev")
        }

        // Bucket 2: Minimalista / Inicial Compacta
        val bucketCompact = mutableListOf<String>()
        if (secondaryToken.isNotBlank()) {
            bucketCompact.add("${baseName}_${secondaryToken.take(1)}")
            bucketCompact.add("${baseName.take(1)}_${secondaryToken}")
            bucketCompact.add("${baseName}.${secondaryToken.take(1)}")
            bucketCompact.add("${baseName.take(1)}${secondaryToken}")
        } else {
            bucketCompact.add("${baseName}.ok")
            bucketCompact.add("${baseName}.me")
            bucketCompact.add("the_${baseName}")
        }

        // Bucket 3: Conversacional & Cercano ("Soy", "Hola", "Es")
        val bucketConversational = mutableListOf<String>()
        bucketConversational.add("soy${baseName}")
        bucketConversational.add("hola_${baseName}")
        bucketConversational.add("soy_${baseName}")
        bucketConversational.add("es_${baseName}")

        // Bucket 4: Moniker Personal / Derivado de Correo
        val bucketMoniker = mutableListOf<String>()
        if (emailCleanAlpha.isNotBlank() && emailCleanAlpha != baseName && emailCleanAlpha.length in 3..18) {
            bucketMoniker.add(emailCleanAlpha)
        }
        bucketMoniker.add("${baseName}_real")
        bucketMoniker.add("${baseName}.now")
        bucketMoniker.add("${baseName}.hub")
        bucketMoniker.add("iam${baseName}")

        // Bucket 5: Insignia Temporal / Badge Moderno
        val bucketBadge = mutableListOf<String>()
        bucketBadge.add("${baseName}${currentYear}")
        bucketBadge.add("${baseName}.club")
        bucketBadge.add("${baseName}_vip")
        bucketBadge.add("${baseName}24")
        bucketBadge.add("${baseName}_99")

        val buckets = listOf(bucketCanonical, bucketCompact, bucketConversational, bucketMoniker, bucketBadge)
        val selected = mutableListOf<String>()

        // Seleccionar 1 de cada bucket según el batchIndex, rotando si es necesario
        for (i in buckets.indices) {
            val bucket = buckets[i]
            for (step in 0 until bucket.size) {
                val candidateIdx = (batchIndex + step) % bucket.size
                val rawCand = bucket[candidateIdx]
                val cand = rawCand.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.lowercase(Locale.ROOT)
                if (cand.length in 3..20 &&
                    !isTaken(cand) &&
                    cand !in selected &&
                    FifoContentFilter.validateUsername(cand).isValid
                ) {
                    selected.add(cand)
                    break
                }
            }
        }

        // Si algún bucket quedó vacío (por duplicado o disponibilidad), rellenar con candidatos limpios no usados
        val fallbackPool = mutableListOf<String>()
        buckets.flatten().forEach { raw ->
            val cand = raw.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.lowercase(Locale.ROOT)
            if (cand !in selected && cand.length in 3..20 && !isTaken(cand) && FifoContentFilter.validateUsername(cand).isValid) {
                fallbackPool.add(cand)
            }
        }
        var fIdx = 0
        while (selected.size < 5 && fIdx < fallbackPool.size) {
            val cand = fallbackPool[fIdx]
            if (cand !in selected) {
                selected.add(cand)
            }
            fIdx++
        }

        // Si aún así no llegamos a 5, generar sufijos únicos con saltos no repetitivos
        val saltSuffixes = listOf("ok", currentYear.toString(), "real", "pro", "cl", "top", "plus", "7", "x")
        var sIdx = 0
        while (selected.size < 5 && sIdx < saltSuffixes.size) {
            val suf = saltSuffixes[(batchIndex + sIdx) % saltSuffixes.size]
            val cand = if (baseName.length + suf.length + 1 <= 20) "${baseName}_$suf" else "${baseName.take(20 - suf.length)}$suf"
            if (!isTaken(cand) && cand !in selected && FifoContentFilter.validateUsername(cand).isValid) {
                selected.add(cand)
            }
            sIdx++
        }

        return selected.take(5).map { "@$it" }
    }
}
