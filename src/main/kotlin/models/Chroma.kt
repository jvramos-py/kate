package dev.notnull.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChromaCollection(
    val name: String,
    val id: String? = null,
    val metadata: JsonObject? = null
)

@Serializable
data class ChromaCreateCollectionRequest(
    val name: String,
    val get_or_create: Boolean = true
)

@Serializable
data class ChromaAddRequest(
    val ids: List<String>,
    val embeddings: List<List<Float>>,
    val documents: List<String>,
    val metadatas: List<JsonObject>? = null
)

@Serializable
data class ChromaDeleteRequest(
    val ids: List<String>? = null,
    val where: JsonObject? = null
)

@Serializable
data class ChromaGetRequest(
    val ids: List<String>? = null,
    val where: JsonObject? = null,
    val limit: Int? = null,
    val sort: String? = null
)

@Serializable
data class ChromaGetResponse(
    val ids: List<String>,
    val metadatas: List<JsonObject>? = null,
    val documents: List<String>? = null
)

@Serializable
data class ChromaQueryRequest(
    val queryEmbeddings: List<List<Float>>,
    val nResults: Int = 5,
    val include: List<String> = listOf("documents", "distances", "metadatas")
)

@Serializable
data class ChromaQueryResponse(
    val ids: List<List<String>>,
    val documents: List<List<String>>,
    val distances: List<List<Float>>? = null
)
