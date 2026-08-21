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

    val speakerService = Speaker(
        "/home/jose/IdeaProjects/kate/src/main/resources/voices/it_IT-paola-medium.onnx",
        "/home/jose/IdeaProjects/kate/lavalink/sounds"
    )
    val discordClient = gateway.restClient
    val applicationId = discordClient.applicationId.block()!!

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
            val collectionId = chromaClient.getOrCreateCollection(event.message.channelId.asString())
            handleMessage(event, ollamaClient, chromaClient, speakerService, collectionId)
        }.doOnError { e ->
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
    speaker: Speaker,
    collectionId: String?
) {
    val content = event.message.content
    val channel = event.message.channel.awaitSingle()
    val author = event.message.author.orElse(null) ?: return
    if (author.isBot) return

    val isKateCalled = content.contains("Kate", ignoreCase = true)

    var context: String? = null
    if (collectionId != null && isKateCalled) {
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
        ollamaClient.generateResponse(content)
    } else null
    if (response != null) {
        channel.createMessage(response).awaitSingle()
        val file = speaker.generateSpeech(response, "${author.username}_${System.currentTimeMillis()}.wav")
        println("[DEBUG] Respondendo $content com $response")
    }

    if (collectionId != null) {
        chromaClient.getOrCreateCollection(collectionId)
        val doc = if (response != null) {
            "Usuário ${author.username}: $content\nKate: $response"
        } else {
            "Usuário ${author.username}: $content"
        }

        val docEmbed = ollamaClient.getEmbeddings(doc)
        if (docEmbed != null) {
            val metadata = buildJsonObject {
                put("userId", author.id.asLong().toString())
                put("timestamp", System.currentTimeMillis())
            }

            val userFilter = buildJsonObject {
                put("userId", author.id.asLong().toString())
            }

            val existingDocs = chromaClient.getDocuments(collectionId, where = userFilter)
            if (existingDocs != null && existingDocs.ids.size >= 8) {
                val sortedIds = existingDocs.ids.sorted()
                val idsToDelete = sortedIds.take(existingDocs.ids.size - 7)
                chromaClient.deleteDocuments(collectionId, ids = idsToDelete)
            }

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
