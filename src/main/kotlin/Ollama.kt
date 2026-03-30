package dev.notnull

import kotlinx.serialization.Serializable

@Serializable
data class OllamaRequest(
    val prompt: String,
    val model: String,
    val stream: Boolean,
)

@Serializable
data class OllamaEmbedRequest(
    val model: String,
    val input: String,
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