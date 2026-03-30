package dev.notnull

import dev.arbjerg.lavalink.client.LavalinkClient
import dev.arbjerg.lavalink.client.NodeOptions
import dev.arbjerg.lavalink.client.player.TrackLoaded
import dev.arbjerg.lavalink.libraries.discord4j.leave
import dev.arbjerg.lavalink.protocol.v4.VoiceState
import discord4j.common.util.Snowflake
import discord4j.core.DiscordClient
import discord4j.core.event.domain.VoiceServerUpdateEvent
import discord4j.core.event.domain.interaction.ApplicationCommandInteractionEvent
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.discordjson.json.ApplicationCommandRequest
import discord4j.discordjson.json.gateway.VoiceStateUpdate
import discord4j.gateway.json.GatewayPayload
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

fun main(): Unit = runBlocking {
    val ollamaClient = OllamaClient()
    val chromaClient = ChromaClient()

    val collectionId = chromaClient.getOrCreateCollection("messages")
    println("[DEBUG] collectionId obtido: $collectionId")

    val botToken = System.getenv("BOT_TOKEN") ?: ""
    val botClient = DiscordClient.create(botToken)
    val gateway = botClient.login().awaitSingle()

    val userId = gateway.selfId.asLong()
    val lavalink = LavalinkClient(userId)

    val joinCommand = ApplicationCommandRequest.builder()
        .name("join")
        .description("Faz a Kate entrar no seu canal de voz para trocar uma ideia")
        .build()

    val leaveCommand = ApplicationCommandRequest.builder()
        .name("leave")
        .description("Faz a Kate sair do seu canal de voz")
        .build()

    lavalink.addNode(
        NodeOptions.Builder()
            .setName("kate-voice")
            .setServerUri(URI.create("http://127.0.0.1:2333"))
            .setPassword("youshallnotpass")
            .build()
    )
    val discordClient = gateway.restClient
    val applicationId = discordClient.applicationId.block()!!
    println("✅ Bot conectado como ${gateway.self.awaitSingle().username}")

    discordClient.applicationService.bulkOverwriteGlobalApplicationCommand(
        applicationId,
        listOf(joinCommand, leaveCommand)
    ).doOnNext { println(it) }.subscribe()
    gateway.on(VoiceServerUpdateEvent::class.java).subscribe { event ->
        val guildId = event.guildId.asLong()
        val link = lavalink.getOrCreateLink(guildId)

        gateway.getVoiceStateById(event.guildId, gateway.selfId)
            .subscribe { voiceState ->
                link.onVoiceServerUpdate(
                    VoiceState(
                        event.token,
                        event.endpoint!!,
                        voiceState.sessionId,
                        voiceState.channelId.map { it.asString() }.orElse("")
                    )
                )
                println("📡 Dados de voz enviados ao Lavalink para a guilda: $guildId")
            }
    }
    gateway.on(MessageCreateEvent::class.java).subscribe { event ->
        mono {
            handleMessage(event, ollamaClient, chromaClient, collectionId)
        }.doOnError { e ->
            println("[DEBUG] ERRO ao processar mensagem: ${e.message}")
            e.printStackTrace()
        }.subscribe()
    }

    gateway.on(ApplicationCommandInteractionEvent::class.java).subscribe { event ->
        mono {
            if (event.commandName.equals("leave", ignoreCase = true)) {
                event.reply("Leavarei").subscribe()
                gateway.leave(event.interaction.guildId.get()).subscribe()
            }

            if (event.commandName.equals("join", ignoreCase = true)) {
                val guildId = event.interaction.guildId.map { it.asLong() }.orElse(null) ?: return@mono
                val member = event.user.asMember(Snowflake.of(guildId)).awaitSingle()
                val voiceState = member.voiceState.awaitSingle()
                val voiceChannel = voiceState.channel.awaitSingle()

                gateway.gatewayClientGroup.multicast(
                    GatewayPayload.voiceStateUpdate(
                        VoiceStateUpdate.builder()
                            .guildId(guildId)
                            .channelId(voiceChannel.id.asLong())
                            .selfMute(false)
                            .selfDeaf(false)
                            .build()
                    )
                ).subscribe()

                val identifier = "/opt/Lavalink/sounds/voce-nao-tem-aura.mp3"
                lavalink.getOrCreateLink(guildId).loadItem(identifier).subscribe { loadResult ->
                    if (loadResult is TrackLoaded) {
                        lavalink.getOrCreateLink(guildId).getPlayer().subscribe { player ->
                            player.setTrack(loadResult.track).subscribe()
                            println("🎵 Kate Kitty começou a tocar: $identifier")
                        }
                    } else {
                        println("❌ Falha no Lavalink: ${loadResult::class.simpleName}")
                    }
                }

                event.reply("Opa! Tô na área!").awaitSingle()
            }

        }.subscribe()


    }
    gateway.onDisconnect().awaitSingle()

}

    suspend fun handleMessage(
        event: MessageCreateEvent,
        ollamaClient: OllamaClient,
        chromaClient: ChromaClient,
        collectionId: String?
    ) {
        val content = event.message.content
        val channel = event.message.channel.awaitSingle()
        val author = event.message.author.orElse(null) ?: return
        if (author.isBot) return

        println("[DEBUG] Processando mensagem de ${author.username}: $content")

        val isKateCalled = content.contains("Kate", ignoreCase = true)

        var context: String? = null
        if (collectionId != null && isKateCalled) {
            println("[DEBUG] Kate foi chamada. Buscando contexto no ChromaDB...")
            val queryEmbed = ollamaClient.getEmbeddings(content)
            if (queryEmbed != null) {
                val results = chromaClient.query(collectionId, queryEmbed)
                if (results.isNotEmpty()) {
                    context = results.joinToString("\n")
                    println("[DEBUG] Contexto recuperado: ${context.take(50)}...")
                } else {
                    println("[DEBUG] Nenhum contexto similar encontrado.")
                }
            } else {
                println("[DEBUG] Falha ao gerar embeddings para a query.")
            }
        }

        val response = if (isKateCalled) {
            ollamaClient.generateResponse(content, context)
        } else null

        if (response != null) {
            println("[DEBUG] Respondendo $content com $response")
            channel.createMessage(response).awaitSingle()
        }

        if (collectionId != null) {
            val doc = if (response != null) {
                "Usuário ${author.username}: $content\nKate: $response"
            } else {
                "Usuário ${author.username}: $content"
            }

            println("[DEBUG] Gerando embeddings para salvar documento: ${doc.take(50)}...")
            val docEmbed = ollamaClient.getEmbeddings(doc)
            if (docEmbed != null) {
                val metadata = buildJsonObject {
                    put("userId", author.id.asLong().toString())
                    put("timestamp", System.currentTimeMillis())
                }
                
                // Gerencia limite de 8 mensagens por usuário
                val userFilter = buildJsonObject {
                    put("userId", author.id.asLong().toString())
                }
                
                val existingDocs = chromaClient.getDocuments(collectionId, where = userFilter)
                if (existingDocs != null && existingDocs.ids.size >= 8) {
                    val sortedIds = existingDocs.ids.sorted()
                    val idsToDelete = sortedIds.take(existingDocs.ids.size - 7)
                    println("[DEBUG] Removendo ${idsToDelete.size} mensagens antigas do usuário ${author.username}")
                    chromaClient.deleteDocuments(collectionId, ids = idsToDelete)
                }

                println("[DEBUG] Salvando documento no ChromaDB...")
                chromaClient.addDocument(
                    collectionId = collectionId,
                    id = System.currentTimeMillis().toString(),
                    embedding = docEmbed,
                    document = doc,
                    metadata = metadata
                )
                println("[DEBUG] Documento salvo com sucesso.")
            } else {
                println("[DEBUG] ERRO: Falha ao gerar embeddings para o documento. Não foi possível salvar.")
            }
        } else {
            println("[DEBUG] ERRO: collectionId é nulo. Não foi possível salvar.")
        }
    }
