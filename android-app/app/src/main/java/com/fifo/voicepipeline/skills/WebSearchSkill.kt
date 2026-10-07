package com.fifo.voicepipeline.skills

import android.content.Context
import android.net.Uri
import android.util.Log
import com.fifo.voicepipeline.location.FifoLocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Skill para buscar información en internet en tiempo real.
 * Permite a Fifo enriquecer su conocimiento y responder con precisión sobre:
 * - Noticias recientes y hechos de última hora (Google News RSS en español / Chile)
 * - Búsqueda web general en vivo (DuckDuckGo Lite)
 * - Resúmenes enciclopédicos y conceptos (Wikipedia en español REST API)
 * - Clima en tiempo real (Open-Meteo con coordenadas GPS del usuario)
 *
 * Entrega una síntesis hablada lista para TTS y metadatos completos para que Claude o Groq
 * elaboren respuestas atinadas.
 */
class WebSearchSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "WebSearchSkill"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    override val name: String = "web_search"

    override val description: String =
        "Busca en internet en tiempo real para obtener información actualizada: noticias del momento, hechos recientes, resultados deportivos, clima actual, definiciones o datos que enriquezcan la respuesta del agente con fuentes verificadas."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Término, pregunta o palabras clave a buscar en internet (ej: 'noticias de hoy en Chile', 'resultado del partido de Colo Colo ayer', 'presidente de Francia', 'clima en Santiago hoy')"
            },
            "search_type": {
                "type": ["string", "null"],
                "enum": ["general", "news", "encyclopedia", "weather", "null"],
                "description": "Tipo de búsqueda: 'news' para noticias del momento, 'weather' para pronóstico meteorológico, 'encyclopedia' para conceptos históricos o biográficos, 'general' para búsqueda web general. Por defecto 'general'."
            }
        },
        "required": ["query"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult = withContext(Dispatchers.IO) {
        val rawQuery = args["query"]?.toString()?.trim() ?: ""
        val searchTypeRaw = args["search_type"]?.toString()?.trim()?.lowercase() ?: "general"

        if (rawQuery.isBlank()) {
            return@withContext SkillResult(
                success = false,
                spokenFeedback = "No alcancé a escuchar qué tema deseaba buscar en internet."
            )
        }

        // Limpiar query de prefijos de voz comunes
        val query = rawQuery.replace(Regex("(?i)^(busca en internet|busca en la web|averigua en internet|qué dice internet sobre|qué pasó con|busca noticias de|noticias de)\\s+"), "").trim()
            .ifBlank { rawQuery }

        Log.i(TAG, "Ejecutando web_search: '$query' (tipo=$searchTypeRaw)")

        val qLower = query.lowercase()
        val isWeather = searchTypeRaw == "weather" || qLower.contains("clima") || qLower.contains("temperatura") || qLower.contains("llover") || qLower.contains("pronóstico") || qLower.contains("pronostico")
        val isNews = searchTypeRaw == "news" || qLower.contains("noticia") || qLower.contains("hoy") || qLower.contains("ayer") || qLower.contains("última hora") || qLower.contains("ultima hora") || qLower.contains("partido") || qLower.contains("quién ganó") || qLower.contains("quien gano")
        val isEncyclopedia = searchTypeRaw == "encyclopedia" || qLower.startsWith("qué es") || qLower.startsWith("que es") || qLower.startsWith("quién es") || qLower.startsWith("quien es") || qLower.startsWith("definición") || qLower.startsWith("definicion")

        // 1. Clima en tiempo real si la consulta es sobre el tiempo
        if (isWeather) {
            val weatherResult = fetchWeather(query)
            if (weatherResult != null) return@withContext weatherResult
        }

        // 2. Noticias en tiempo real (Google News RSS en español / Chile)
        if (isNews) {
            val newsResult = fetchGoogleNews(query)
            if (newsResult != null) return@withContext newsResult
        }

        // 3. Enciclopedia (Wikipedia en español)
        if (isEncyclopedia) {
            val wikiResult = fetchWikipediaSummary(query)
            if (wikiResult != null) return@withContext wikiResult
        }

        // 4. Búsqueda web general en vivo (DuckDuckGo Lite)
        val ddgResult = fetchDuckDuckGoLite(query)
        if (ddgResult != null) return@withContext ddgResult

        // 5. Fallback a Wikipedia si DDG no arrojó resultados
        val fallbackWiki = fetchWikipediaSummary(query)
        if (fallbackWiki != null) return@withContext fallbackWiki

        // 6. Fallback a Google News
        val fallbackNews = fetchGoogleNews(query)
        if (fallbackNews != null) return@withContext fallbackNews

        SkillResult(
            success = false,
            spokenFeedback = "Intenté buscar información sobre $query en internet, pero no encontré datos concluyentes en este momento.",
            data = mapOf("query" to query, "found" to false)
        )
    }

    /**
     * Consulta el clima actual en tiempo real usando Open-Meteo API.
     */
    private fun fetchWeather(query: String): SkillResult? {
        return try {
            val currentLoc = FifoLocationHelper.getCurrentLocation(context)
            val lat = currentLoc.latitude
            val lon = currentLoc.longitude
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&timezone=auto"

            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val current = json.optJSONObject("current") ?: return null

                val temp = current.optDouble("temperature_2m", Double.NaN)
                val apparent = current.optDouble("apparent_temperature", Double.NaN)
                val code = current.optInt("weather_code", -1)
                val wind = current.optDouble("wind_speed_10m", 0.0)

                val conditionText = when (code) {
                    0 -> "cielo completamente despejado"
                    1, 2 -> "cielo mayormente despejado con pocas nubes"
                    3 -> "cielo cubierto de nubes"
                    45, 48 -> "presencia de niebla"
                    51, 53, 55 -> "llovizna suave"
                    61, 63, 65 -> "lluvia"
                    80, 81, 82 -> "chubascos intermitentes"
                    95, 96, 99 -> "tormenta eléctrica"
                    else -> "tiempo estable"
                }

                val tempInt = if (!temp.isNaN()) temp.toInt() else 20
                val apparentInt = if (!apparent.isNaN()) apparent.toInt() else tempInt

                val feedback = "En ${currentLoc.city} tenemos actualmente unos $tempInt grados, con sensación térmica de $apparentInt grados y $conditionText."

                SkillResult(
                    success = true,
                    spokenFeedback = feedback,
                    data = mapOf(
                        "city" to currentLoc.city,
                        "temperature_celsius" to tempInt,
                        "apparent_celsius" to apparentInt,
                        "condition" to conditionText,
                        "wind_kmh" to wind.toInt()
                    ),
                    additionalData = mapOf("weather_summary" to feedback)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando clima: ${e.message}")
            null
        }
    }

    /**
     * Consulta Google News RSS en español (Chile) para noticias recientes y de última hora.
     */
    private fun fetchGoogleNews(query: String): SkillResult? {
        return try {
            val url = "https://news.google.com/rss/search?q=${Uri.encode(query)}&hl=es-419&gl=CL&ceid=CL:es-419"
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null

                val itemRegex = Regex("<item>([\\s\\S]*?)</item>")
                val titleRegex = Regex("<title>([\\s\\S]*?)</title>")
                val sourceRegex = Regex("<source[^>]*>([\\s\\S]*?)</source>")

                val matches = itemRegex.findAll(body).take(3).toList()
                if (matches.isEmpty()) return null

                val articles = mutableListOf<String>()
                for (m in matches) {
                    val rawTitle = titleRegex.find(m.value)?.groupValues?.get(1) ?: continue
                    val cleanTitle = cleanHtml(rawTitle)
                    val rawSource = sourceRegex.find(m.value)?.groupValues?.get(1) ?: ""
                    val cleanSource = cleanHtml(rawSource)

                    val itemStr = if (cleanSource.isNotBlank() && !cleanTitle.contains(cleanSource, ignoreCase = true)) {
                        "$cleanTitle, según $cleanSource"
                    } else {
                        cleanTitle
                    }
                    articles.add(itemStr)
                }

                if (articles.isEmpty()) return null

                val spoken = when (articles.size) {
                    1 -> "Revisé las noticias sobre $query: ${articles[0]}."
                    2 -> "Revisé las noticias sobre $query: primero, ${articles[0]}. Además, ${articles[1]}."
                    else -> "Revisé las noticias sobre $query: ${articles[0]}. Por otra parte, ${articles[1]}."
                }

                SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf(
                        "query" to query,
                        "articles_count" to articles.size,
                        "sources" to "Google News"
                    ),
                    additionalData = mapOf("news_summary" to articles.joinToString("\n- "))
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando Google News: ${e.message}")
            null
        }
    }

    /**
     * Consulta resúmenes enciclopédicos desde Wikipedia en español REST API.
     */
    private fun fetchWikipediaSummary(query: String): SkillResult? {
        return try {
            // Paso 1: Búsqueda del artículo más relevante
            val searchUrl = "https://es.wikipedia.org/w/api.php?action=query&list=search&srsearch=${Uri.encode(query)}&format=json&utf8=1&srlimit=1"
            val searchReq = Request.Builder().url(searchUrl).header("User-Agent", USER_AGENT).build()

            val title = httpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val searchArr = json.optJSONObject("query")?.optJSONArray("search") ?: return null
                if (searchArr.length() == 0) return null
                searchArr.getJSONObject(0).optString("title", "")
            }

            if (title.isBlank()) return null

            // Paso 2: Resumen limpio del artículo
            val summaryUrl = "https://es.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(title)}"
            val summaryReq = Request.Builder().url(summaryUrl).header("User-Agent", USER_AGENT).build()

            httpClient.newCall(summaryReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val extract = json.optString("extract", "")
                if (extract.isBlank()) return null

                // Extraer los primeros 1 o 2 enunciados para concisión verbal
                val cleanExtract = cleanHtml(extract)
                val sentences = cleanExtract.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")

                val spoken = "Revisé en la enciclopedia sobre $title: $sentences"
                SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf(
                        "title" to title,
                        "source" to "Wikipedia en español"
                    ),
                    additionalData = mapOf("article_extract" to cleanExtract)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando Wikipedia: ${e.message}")
            null
        }
    }

    /**
     * Consulta DuckDuckGo Lite para búsquedas web generales en tiempo real.
     */
    private fun fetchDuckDuckGoLite(query: String): SkillResult? {
        return try {
            val formBody = FormBody.Builder()
                .add("q", query)
                .build()

            val req = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/")
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null

                val snippetRegex = Regex("<td class=['\"]result-snippet['\"]>([\\s\\S]*?)</td>")
                val matches = snippetRegex.findAll(html).take(2).toList()
                if (matches.isEmpty()) return null

                val snippets = matches.map { cleanHtml(it.groupValues[1]) }
                    .filter { it.isNotBlank() && it.length > 15 }

                if (snippets.isEmpty()) return null

                val spoken = if (snippets.size == 1) {
                    "Revisé en la web sobre $query: ${snippets[0]}"
                } else {
                    "Revisé en la web sobre $query: ${snippets[0]}. Además, se indica que ${snippets[1]}"
                }

                SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf(
                        "query" to query,
                        "results_count" to snippets.size,
                        "source" to "Búsqueda web"
                    ),
                    additionalData = mapOf("web_snippets" to snippets.joinToString("\n- "))
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error consultando DuckDuckGo Lite: ${e.message}")
            null
        }
    }

    /**
     * Limpia etiquetas HTML y entidades codificadas para texto apto para TTS.
     */
    private fun cleanHtml(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "y")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
