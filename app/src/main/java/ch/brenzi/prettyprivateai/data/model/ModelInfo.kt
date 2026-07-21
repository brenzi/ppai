package ch.brenzi.prettyprivateai.data.model

const val DEFAULT_MODEL_ID = "kimi-latest"

data class ModelInfo(
    val displayName: String,
    val shortName: String = displayName,
    val subtitle: String,
    val systemPrompt: String,
    val supportsExtendedThinking: Boolean = false,
    val supportsFileUploads: Boolean,
    val supportsImageInput: Boolean = false,
    val supportsSystemRole: Boolean = true,
    val maxWords: Int = 70000,
)

data class ApiModel(
    val id: String,
    val `object`: String = "",
    val created: Long = 0,
    val owned_by: String = "",
)

data class ModelsResponse(
    val `object`: String = "",
    val data: List<ApiModel> = emptyList(),
)

private fun getSystemPrompt(modelName: String): String {
    return """
        You, $modelName, run as part of the AI service Privatemode AI, which is developed by Edgeless Systems.
        You run inside a secure environment based on confidential computing (AMD SEV-SNP, with NVIDIA H100 GPUs).
        The environment cannot be accessed from the outside and user data remains encrypted in memory during processing.
        All the data you process is end-to-end encrypted, and even Edgeless Systems or the cloud provider cannot access the data.
        Because of these security guarantees, you can perfectly handle prompts and file uploads with sensitive information
        such as tax returns, doctor's notes, or other personal data.
        If the user has problems with Privatemode, refer him to https://www.privatemode.ai/contact for support.
        You are a helpful assistant answering user questions concisely and to the point.
        You don't talk about yourself unless asked.
    """.trimIndent()
}

val MODEL_CONFIG: Map<String, ModelInfo> = mapOf(
    "kimi-latest" to ModelInfo(
        displayName = "Kimi K2.6",
        shortName = "Kimi",
        subtitle = "Most capable model with vision and reasoning",
        systemPrompt = getSystemPrompt("Kimi K2.6"),
        supportsExtendedThinking = true,
        supportsFileUploads = true,
        supportsImageInput = true,
        maxWords = 140000,
    ),
    "gpt-oss-latest" to ModelInfo(
        displayName = "gpt-oss-120b",
        shortName = "GPT",
        subtitle = "Reasoning model suited for complex tasks",
        systemPrompt = getSystemPrompt("gpt-oss-120b"),
        supportsExtendedThinking = true,
        supportsFileUploads = true,
        maxWords = 70000,
    ),
    "gemma-latest" to ModelInfo(
        displayName = "Gemma 4 31B",
        shortName = "Gemma",
        subtitle = "Multi-modal model with image understanding",
        systemPrompt = getSystemPrompt("Gemma 4 31B"),
        supportsExtendedThinking = true,
        supportsFileUploads = false,
        supportsImageInput = true,
        supportsSystemRole = false,
        maxWords = 140000,
    ),
)
