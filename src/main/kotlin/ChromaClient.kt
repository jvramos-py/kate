package dev.notnull

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChromaCollection(
    val name: String,
    val id: String? = null,
    val metadata: JsonObject? = null
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
    val query_embeddings: List<List<Float>>,
    val n_results: Int = 5,
    val include: List<String> = listOf("documents", "distances", "metadatas")
)

@Serializable
data class ChromaQueryResponse(
    val ids: List<List<String>>,
    val documents: List<List<String>>,
    val distances: List<List<Float>>? = null
)

class ChromaClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    private val BASE_URL = "http://localhost:8001/api/v2/tenants/default_tenant/databases/default_database"

    suspend fun getOrCreateCollection(name: String): String? {
        return try {
            val response = client.post("$BASE_URL/collections") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("name" to name, "get_or_create" to true))
            }
            if (response.status.isSuccess()) {
                val bodyText = response.bodyAsText()
                println("[DEBUG] Resposta Chroma (Collections): $bodyText")
                val collection = Json { ignoreUnknownKeys = true }.decodeFromString<ChromaCollection>(bodyText)
                collection.id
            } else {
                val errorBody = response.bodyAsText()
                println("Erro ao obter/criar coleção Chroma: ${response.status} - $errorBody")
                null
            }
        } catch (e: Exception) {
            println("Erro na requisição Chroma: ${e.message}")
            null
        }
    }

    suspend fun addDocument(
        collectionId: String,
        id: String,
        embedding: List<Float>,
        document: String,
        metadata: JsonObject? = null
    ) {
        try {
            val response = client.post("$BASE_URL/collections/$collectionId/upsert") {
                contentType(ContentType.Application.Json)
                setBody(ChromaAddRequest(
                    ids = listOf(id),
                    embeddings = listOf(embedding),
                    documents = listOf(document),
                    metadatas = if (metadata != null) listOf(metadata) else null
                ))
            }
            if (!response.status.isSuccess()) {
                println("Erro ao adicionar documento no Chroma (HTTP ${response.status}): ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            println("Erro ao adicionar documento no Chroma: ${e.message}")
        }
    }

    suspend fun getDocuments(
        collectionId: String,
        where: JsonObject? = null,
        limit: Int? = null
    ): ChromaGetResponse? {
        return try {
            val response = client.post("$BASE_URL/collections/$collectionId/get") {
                contentType(ContentType.Application.Json)
                setBody(ChromaGetRequest(where = where, limit = limit))
            }
            if (response.status.isSuccess()) {
                response.body<ChromaGetResponse>()
            } else {
                null
            }
        } catch (e: Exception) {
            println("Erro ao obter documentos do Chroma: ${e.message}")
            null
        }
    }

    suspend fun deleteDocuments(collectionId: String, ids: List<String>? = null, where: JsonObject? = null) {
        try {
            client.post("$BASE_URL/collections/$collectionId/delete") {
                contentType(ContentType.Application.Json)
                setBody(ChromaDeleteRequest(ids = ids, where = where))
            }
        } catch (e: Exception) {
            println("Erro ao deletar documentos do Chroma: ${e.message}")
        }
    }

    suspend fun query(collectionId: String, queryEmbedding: List<Float>, nResults: Int = 3): List<String> {
        return try {
            val response = client.post("$BASE_URL/collections/$collectionId/query") {
                contentType(ContentType.Application.Json)
                setBody(ChromaQueryRequest(
                    query_embeddings = listOf(queryEmbedding),
                    n_results = nResults
                ))
            }
            if (response.status.isSuccess()) {
                val result = response.body<ChromaQueryResponse>()
                result.documents.flatten()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("Erro ao consultar Chroma: ${e.message}")
            emptyList()
        }
    }
}
