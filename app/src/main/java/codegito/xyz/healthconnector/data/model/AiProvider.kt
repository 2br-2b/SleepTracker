package codegito.xyz.healthconnector.data.model

enum class AiProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val requiresBaseUrl: Boolean,
    val defaultModel: String,
    val defaultBaseUrl: String? = null,
    val notes: String,
    /** Whether this provider supports the OpenAI `reasoning_effort` parameter (o-series models). */
    val supportsReasoningEffort: Boolean = false
) {
    OPENAI_COMPAT(
        displayName = "OpenAI-compatible (via Koog)",
        requiresApiKey = true,
        requiresBaseUrl = true,
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1",
        notes = "Use this for OpenAI-compatible providers that expose a /v1 chat/completions-compatible API.",
        supportsReasoningEffort = true
    ),
    ANTHROPIC(
        displayName = "Anthropic (via Koog)",
        requiresApiKey = true,
        requiresBaseUrl = false,
        defaultModel = "claude-3-5-haiku-latest",
        notes = "Uses Anthropic-hosted API defaults in Koog."
    ),
    GEMINI(
        displayName = "Google Gemini (via Koog)",
        requiresApiKey = true,
        requiresBaseUrl = false,
        defaultModel = "gemini-1.5-flash",
        notes = "Uses Google-hosted Gemini API defaults in Koog."
    ),
    OLLAMA(
        displayName = "Ollama (local/self-hosted)",
        requiresApiKey = false,
        requiresBaseUrl = true,
        defaultModel = "llama3.1:8b",
        defaultBaseUrl = "http://localhost:11434/v1",
        notes = "Best for on-device/local inference. Configure URL to your Ollama host, including /v1 for OpenAI-compatible adapters."
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        requiresApiKey = true,
        requiresBaseUrl = true,
        defaultModel = "openai/gpt-4o-mini",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        notes = "Requires OpenRouter API key; many model families available behind one endpoint.",
        supportsReasoningEffort = true
    ),
    GROQ(
        displayName = "Groq",
        requiresApiKey = true,
        requiresBaseUrl = true,
        defaultModel = "llama-3.1-8b-instant",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        notes = "OpenAI-compatible endpoint with very low-latency inference."
    );

    companion object {
        fun fromStored(value: String?): AiProvider = entries.firstOrNull { it.name == value } ?: OPENAI_COMPAT
    }
}
