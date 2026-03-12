package codegito.xyz.healthconnector.data.model

enum class AiProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val requiresBaseUrl: Boolean
) {
    OPENAI_COMPAT(
        displayName = "OpenAI-compatible (via Koog)",
        requiresApiKey = true,
        requiresBaseUrl = true
    ),
    ANTHROPIC(
        displayName = "Anthropic (via Koog)",
        requiresApiKey = true,
        requiresBaseUrl = false
    ),
    GEMINI(
        displayName = "Google Gemini (via Koog)",
        requiresApiKey = true,
        requiresBaseUrl = false
    );

    companion object {
        fun fromStored(value: String?): AiProvider = entries.firstOrNull { it.name == value } ?: OPENAI_COMPAT
    }
}
