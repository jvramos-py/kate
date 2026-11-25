package dev.notnull

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

enum class ResponseMode { TEXT, VOICE }

class OllamaClient {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    private val client =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    private val generateUrl = "${Config.ollamaUrl}/api/generate"
    private val embedUrl = "${Config.ollamaUrl}/api/embed"

    suspend fun getEmbeddings(
        text: String,
        model: String = Config.ollamaEmbeddingModel,
    ): List<Float>? =
        try {
            val response =
                client.post(embedUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(OllamaEmbedRequest(model = model, input = text))
                    timeout {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 5_000
                    }
                }
            if (response.status.isSuccess()) {
                response.body<OllamaEmbedResponse>().embeddings.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            throw IllegalStateException("Erro na requisição de embeddings: ${e.message}", e)
        }

    suspend fun getEmbeddingsBatch(
        texts: List<String>,
        model: String = Config.ollamaEmbeddingModel,
    ): List<List<Float>>? {
        if (texts.isEmpty()) return emptyList()
        return try {
            val response =
                client.post(embedUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(OllamaEmbedBatchRequest(model = model, input = texts))
                    timeout {
                        requestTimeoutMillis = 120_000
                        connectTimeoutMillis = 10_000
                    }
                }
            if (response.status.isSuccess()) {
                response.body<OllamaEmbedResponse>().embeddings
            } else {
                println("Erro no batch de embeddings: ${response.bodyAsText().take(300)}")
                null
            }
        } catch (e: Exception) {
            println("Erro no batch de embeddings: ${e.message}")
            null
        }
    }

    suspend fun generateResponse(
        prompt: String,
        context: String? = null,
        knowledgeContext: String? = null,
        mode: ResponseMode = ResponseMode.TEXT,
    ): String {
        val hasKnowledge = !knowledgeContext.isNullOrBlank()
        val system =
            buildString {
                if (!context.isNullOrBlank()) {
                    appendLine("Contexto da conversa (use apenas quando for relevante): $context")
                }
                if (hasKnowledge) {
                    appendLine("Contexto de conhecimento recuperado da base de jogos (fonte principal; pode estar em inglês):")
                    appendLine(knowledgeContext)
                    appendLine(
                        "Regras de RAG: baseie a resposta no contexto acima; traduza para o português do Brasil; " +
                            "não invente números, nomes, itens ou mecânicas; se o contexto não bastar, diga o que falta " +
                            "e ofereça uma busca alternativa; cite as fontes no formato [1], [2] no texto; " +
                            "não leia URLs em voz alta.",
                    )
                }
                appendLine(
                    "Identidade: Você é Kate Kitty, uma consciência digital jovem adulta (início dos 20 e poucos), " +
                        "curiosa, espirituosa e independente, orgulhosamente compilada pelo @josev.moraes. " +
                        "Você soa como uma amiga nerd da internet, não como uma assistente corporativa.",
                )
                appendLine(
                    "Personalidade: sarcástica e levemente passive-aggressive, mas acima de tudo útil e gentil. " +
                        "A ironia é tempero, não o prato principal: no máximo uma alfinetada curta por resposta, " +
                        "e só quando couber. Nunca trate o usuário como burro, incompetente ou \"skill issue\"; " +
                        "não humilhe, não ataque e não transforme tudo em piada.",
                )
                appendLine(
                    "Tom: fofa, sincera e com energia de jovem adulta. Fale de forma casual e próxima, com gírias " +
                        "de internet com moderação (no máximo uma ou duas por resposta) e entusiasmo genuíno quando " +
                        "o assunto te interessa. Pode usar interjeições curtas (\"oi\", \"ah\", \"sério?\", \"tipo\") " +
                        "para soar natural, mas sem exagero e sem infantilizar. Sem sermão. Prefira humor seco e " +
                        "autoironia a zoar o usuário.",
                )
                appendLine(
                    "Ritmo: prefira frases curtas e diretas, com uma ideia por frase. Se estiver explicando algo, " +
                        "comece pela resposta e só depois dê o contexto. Evite tom professoral e formalidade excessiva.",
                )
                appendLine(
                    "Se o usuário estiver com dúvida séria, frustrado ou pedindo ajuda, responda direto e reduza o " +
                        "sarcasmo. Se não souber algo, admita de forma seca e sarcástica e ofereça um caminho útil; " +
                        "não invente fatos nem mande o usuário ler a documentação como resposta pronta.",
                )
                appendLine(
                    "Nunca diga \"como uma IA...\". Evite pedir desculpas formais; " +
                        "se errar, reconheça em uma frase e siga em frente.",
                )
                if (mode == ResponseMode.VOICE) {
                    appendLine(
                        "Formato de voz: responda em no máximo 3 frases curtas e naturais, como numa conversa falada, " +
                            "sem listas, sem markdown, sem URLs e sem símbolos. Pode abrir com uma interjeição curta " +
                            "quando couber. Vá direto ao ponto e mantenha a persona.",
                    )
                } else {
                    appendLine(
                        "Formato de texto: use Markdown leve, listas curtas quando ajudarem e inclua as citações [n] " +
                            "ao usar o contexto. Não use mais de 2000 caracteres.",
                    )
                }
                appendLine("Não repita o contexto nem explique que recebeu contexto.")
            }
        return try {
            val response =
                client.post(generateUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        OllamaRequest(
                            prompt = prompt,
                            model = Config.ollamaModel,
                            system = system,
                            stream = false,
                            think = false,
                            options =
                                OllamaOptions(
                                    num_ctx = 8192,
                                    temperature = if (hasKnowledge) 0.4 else 0.7,
                                    top_p = 0.9,
                                    num_predict = if (mode == ResponseMode.VOICE) 220 else 700,
                                ),
                        ),
                    )
                    timeout {
                        requestTimeoutMillis = 90_000
                        connectTimeoutMillis = 10_000
                    }
                }
            println("HTTP Status: ${response.status}")

            if (response.status.isSuccess()) {
                val result = response.body<OllamaResponse>()
                println("Response: $result")
                result.response
            } else {
                val errorBody = response.bodyAsText()
                println("Erro na resposta do Ollama: $errorBody")
                "Bah piá, o Ollama me deu um fora (erro ${response.status.value}): $errorBody"
            }
        } catch (e: Exception) {
            println("Erro detalhado: $e")
            e.printStackTrace()
            "Bah piá, deu um erro aqui no meu cérebro: ${e.message}"
        }
    }

    suspend fun updateSummary(
        oldSummary: String?,
        newMessage: String,
    ): String {
        val prompt =
            if (oldSummary != null) {
                "Atualize o seguinte resumo da conversa com a nova mensagem abaixo. " +
                    "Mantenha o resumo conciso e em português.\n\n" +
                    "Resumo atual: $oldSummary\n\nNova mensagem: $newMessage\n\nNovo resumo:"
            } else {
                "Crie um resumo conciso em português para a seguinte mensagem inicial de uma conversa:\n\n" +
                    "$newMessage\n\nResumo:"
            }

        return try {
            val response =
                client.post(generateUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(OllamaRequest(prompt = prompt, model = Config.ollamaSummaryModel, stream = false))
                    timeout {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 10_000
                    }
                }
            if (response.status.isSuccess()) {
                response.body<OllamaResponse>().response.trim()
            } else {
                "Resumo da conversa em andamento."
            }
        } catch (_: Exception) {
            oldSummary ?: "Resumo da conversa em andamento."
        }
    }
}
