package dev.notnull

import dev.notnull.models.ChromaAddRequest
import dev.notnull.models.ChromaCollection
import dev.notnull.models.ChromaCreateCollectionRequest
import dev.notnull.models.ChromaDeleteRequest
import dev.notnull.models.ChromaGetRequest
import dev.notnull.models.ChromaGetResponse
import dev.notnull.models.ChromaQueryRequest
import dev.notnull.models.ChromaQueryResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject


private val json = Json { ignoreUnknownKeys = true }

class ChromaClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    private val BASE_URL = "http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database"

    suspend fun getOrCreateCollection(name: String): String? {
        return try {
            val response = client.post("$BASE_URL/collections") {
                contentType(ContentType.Application.Json)
                setBody(ChromaCreateCollectionRequest(name, get_or_create = true))
            }
            if (response.status.isSuccess()) {
                val bodyText = response.bodyAsText()
                val collection = json.decodeFromString<ChromaCollection>(bodyText)
                collection.id
            } else {
                val response = client.get("$BASE_URL/collections/$name")
                throw Exception("Erro ao obter/criar coleção Chroma: ${response.status} - ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            throw Exception(e.message)
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
                setBody(
                    ChromaAddRequest(
                        ids = listOf(id),
                        embeddings = listOf(embedding),
                        documents = listOf(document),
                        metadatas = if (metadata != null) listOf(metadata) else null
                    )
                )
            }
            if (!response.status.isSuccess()) {
                println("Erro ao adicionar documento no Chroma (HTTP ${response.status}): ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            throw Exception("Erro ao adicionar documento no Chroma: ${e.message}")
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
            throw Exception("Erro ao obter documentos do Chroma: ${e.message}")
        }
    }

    suspend fun deleteDocuments(collectionId: String, ids: List<String>? = null, where: JsonObject? = null) {
        try {
            client.post("$BASE_URL/collections/$collectionId/delete") {
                contentType(ContentType.Application.Json)
                setBody(ChromaDeleteRequest(ids = ids, where = where))
            }
        } catch (e: Exception) {
            throw Exception("Erro ao deletar documentos do Chroma: ${e.message}")
        }
    }

    suspend fun query(collectionId: String, queryEmbedding: List<Float>, nResults: Int = 3): List<String> {
        return try {
            val response = client.post("$BASE_URL/collections/$collectionId/query") {
                contentType(ContentType.Application.Json)
                setBody(
                    ChromaQueryRequest(
                        queryEmbeddings = listOf(queryEmbedding),
                        nResults = nResults
                    )
                )
            }
            if (response.status.isSuccess()) {
                val result = response.body<ChromaQueryResponse>()
                result.documents.flatten()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            throw Exception("Erro ao consultar o Chroma: ${e.message}")
        }
    }
}
