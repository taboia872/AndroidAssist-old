package com.satory.graphenosai.llm

/**
 * Cloud LLM API providers. All are OpenAI-compatible (chat completions +
SSE streaming), so a single client pipeline serves them all — they differ
only in base URL, API key and catalog endpoint.
 */
object LlmProviders {

    data class Provider(
        val id: String,
        val displayName: String,
        /** Base URL WITHOUT trailing slash; chat endpoint = "$baseUrl/chat/completions" */
        val baseUrl: String,
        /** Model catalog endpoint (OpenAI-compatible GET /models) */
        val modelsUrl: String,
        /** Where the user's API key lives; null = no key required */
        val keySlot: String?
    )

    val OPENROUTER = Provider(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        modelsUrl = "https://openrouter.ai/api/v1/models",
        keySlot = "openrouter_api_key"
    )

    val GROQ = Provider(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        modelsUrl = "https://api.groq.com/openai/v1/models",
        keySlot = "groq_api_key"
    )

    val OLLAMA = Provider(
        id = "ollama",
        displayName = "Ollama Cloud",
        baseUrl = "https://ollama.com/v1",
        modelsUrl = "https://ollama.com/v1/models",
        keySlot = "ollama_api_key"
    )

    val NVIDIA = Provider(
        id = "nvidia",
        displayName = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        modelsUrl = "https://integrate.api.nvidia.com/v1/models",
        keySlot = "nvidia_api_key"
    )

    val ALL = listOf(OPENROUTER, GROQ, OLLAMA, NVIDIA)

    fun byId(id: String): Provider? = ALL.find { it.id == id }
}
