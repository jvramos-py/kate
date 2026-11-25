package dev.notnull

import kotlinx.serialization.Serializable

@Serializable
data class OllamaRequest(
    val prompt: String,
    val model: String,
    val stream: Boolean,
    val system: String? = null,
    val think: Boolean? = null,
    val options: OllamaOptions? = null,
)

@Serializable
data class OllamaOptions(
    val num_ctx: Int? = null,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val num_predict: Int? = null,
)

@Serializable
data class OllamaEmbedRequest(
    val model: String,
    val input: String,
)

@Serializable
data class OllamaEmbedBatchRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
data class OllamaEmbedResponse(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class OllamaResponse(
    val response: String = "",
)
