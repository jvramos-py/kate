package dev.notnull

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class OllamaClient {
    private val defaultJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    private val OLLAMA_URL = "http://127.0.0.1:11434/api/generate"
    private val EMBED_URL = "http://localhost:11434/api/embed"

    suspend fun getEmbeddings(text: String, model: String = "embeddinggemma"): List<Float>? {
        return try {
            val response = client.post(EMBED_URL) {
                contentType(ContentType.Application.Json)
                setBody(OllamaEmbedRequest(model = model, input = text))
            }
            if (response.status.isSuccess()) {
                val result = response.body<OllamaEmbedResponse>()
                result.embeddings.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            throw Exception("Erro na requisição de embeddings: ${e.message}")
        }
    }

    suspend fun generateResponse(prompt: String, context: String? = null): String {
        return try {
            val finalPrompt = if (context != null) {
                "Use o seguinte contexto para responder à pergunta.\nContexto: $context\n\nPergunta: $prompt"
            } else {
                prompt
            }

            val response = client.post(OLLAMA_URL) {
                contentType(ContentType.Application.Json)
                setBody(OllamaRequest(prompt = finalPrompt, stream = false, model = "kate"))
            }
            println("HTTP Status: ${response.status}")

            if (response.status.isSuccess()) {
                val result = response.body<OllamaResponse>()
                println("Response: $result")
                result.response
            } else {
                val errorBody = response.bodyAsText()
                println("Erro na resposta do Ollama: $errorBody")
                "Bah piá, o Ollama me deu um fora (404 ou erro): $errorBody"
            }
        } catch (e: Exception) {
            println("Erro detalhado: $e")
            e.printStackTrace()
            "Bah piá, deu um erro aqui no meu cérebro: ${e.message}"
        }
    }
}