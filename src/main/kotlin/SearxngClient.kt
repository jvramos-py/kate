package dev.notnull

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SearxResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
)

@Serializable
data class SearxResponse(
    val results: List<SearxResult> = emptyList(),
)

class SearxngClient(
    private val baseUrl: String = Config.searxngUrl,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)

    suspend fun search(
        query: String,
        limit: Int = 5,
    ): List<SearxResult> {
        return try {
            val response =
                client.get("$baseUrl/search") {
                    parameter("q", query)
                    parameter("format", "json")
                    parameter("language", "all")
                    timeout {
                        requestTimeoutMillis = 12_000
                        connectTimeoutMillis = 3_000
                    }
                }
            if (!response.status.isSuccess()) {
                println("SearXNG HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
                return emptyList()
            }
            val body = response.bodyAsText()
            if (body.isBlank()) return emptyList()
            json
                .decodeFromString<SearxResponse>(body)
                .results
                .filter { it.title.isNotBlank() && it.url.isNotBlank() }
                .take(limit)
        } catch (e: Exception) {
            println("SearXNG falhou: ${e.message}")
            emptyList()
        }
    }
}
