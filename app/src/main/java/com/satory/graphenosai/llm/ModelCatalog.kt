package com.satory.graphenosai.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Live model catalog from OpenRouter's public GET /models endpoint.
 * Replaces the fossilized hardcoded model list: new models appear in the
 * picker as soon as OpenRouter adds them, without an app update.
 *
 * The endpoint is public (no API key required). On any failure callers fall
 * back to the bundled curated list.
 */
object ModelCatalog {

    private const val TAG = "ModelCatalog"
    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    data class CatalogResult(
        val models: List<com.satory.graphenosai.ui.SettingsManager.ModelInfo>,
        val fetchedAt: Long
    )

    @Volatile
    private var cache: CatalogResult? = null

    /** Returns the cached catalog if fresh enough. */
    fun cached(): List<com.satory.graphenosai.ui.SettingsManager.ModelInfo>? =
        cache?.takeIf { System.currentTimeMillis() - it.fetchedAt < CACHE_TTL_MS }?.models

    /**
     * Fetch the live catalog. Returns null on any failure (caller falls back
     * to the curated list). Results are cached in memory.
     */
    suspend fun fetch(forceRefresh: Boolean = false): List<com.satory.graphenosai.ui.SettingsManager.ModelInfo>? =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                cached()?.let { return@withContext it }
            }

            try {
                val connection = (URL(MODELS_URL).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                        Log.w(TAG, "Models endpoint returned ${connection.responseCode}")
                        return@withContext null
                    }

                    val body = connection.inputStream.bufferedReader().readText()
                    val models = parse(body)
                    if (models.isEmpty()) {
                        Log.w(TAG, "Catalog parsed empty — keeping fallback")
                        return@withContext null
                    }
                    cache = CatalogResult(models, System.currentTimeMillis())
                    Log.i(TAG, "Live catalog loaded: ${models.size} models")
                    models
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Catalog fetch failed: ${e.message}")
                null
            }
        }

    /**
     * Map the OpenRouter catalog JSON onto ModelInfo.
     * Vision support comes from architecture.modality containing "image".
     */
    fun parse(body: String): List<com.satory.graphenosai.ui.SettingsManager.ModelInfo> {
        val root = JSONObject(body)
        val data: JSONArray = root.optJSONArray("data") ?: return emptyList()

        val out = ArrayList<com.satory.graphenosai.ui.SettingsManager.ModelInfo>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue

            val name = m.optString("name", "").ifEmpty { id }
            val modality = m.optJSONObject("architecture")?.optString("modality") ?: ""
            val supportsVision = modality.contains("image")

            // Tool calling support comes from supported_parameters
            val supportedParams = m.optJSONArray("supported_parameters")
            val supportsTools = if (supportedParams != null) {
                (0 until supportedParams.length()).any { supportedParams.optString(it) == "tools" }
            } else {
                true // unknown → assume yes, never block search on missing metadata
            }

            // Description: prompt/completion token price + context length
            val contextLength = m.optJSONObject("top_provider")?.optInt("context_length", 0) ?: 0
            val pricing = m.optJSONObject("pricing")
            val promptPrice = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
            val isFree = id.endsWith(":free") || (promptPrice == 0.0)

            val desc = buildString {
                if (isFree) append("Free")
                else append(String.format("$%.2f/M tokens", promptPrice * 1_000_000))
                if (contextLength > 0) {
                    append(" · ")
                    append(if (contextLength >= 1000) "${contextLength / 1000}K ctx" else "$contextLength ctx")
                }
                if (supportsVision) append(" · vision")
            }

            out.add(
                com.satory.graphenosai.ui.SettingsManager.ModelInfo(
                    id = id,
                    name = name,
                    description = desc,
                    supportsVision = supportsVision,
                    supportsTools = supportsTools
                )
            )
        }

        // Curated order first (well-known models on top), everything else alphabetical
        val priority = listOf(
            "openai/gpt-5.4-mini", "openai/gpt-5.5",
            "anthropic/claude-sonnet-4.6", "anthropic/claude-opus-4.8", "anthropic/claude-haiku-4.5",
            "google/gemini-3.5-flash", "google/gemini-3.1-pro-preview",
            "deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash",
            "qwen/qwen3.7-max", "qwen/qwen3.7-plus"
        )
        val byId = out.associateBy { it.id }
        val head = priority.mapNotNull { byId[it] }
        val rest = out.filter { it.id !in priority.toSet() }
            .sortedBy { it.name.lowercase() }

        return head + rest
    }
}
