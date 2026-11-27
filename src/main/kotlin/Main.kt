package dev.notnull

import discord4j.common.util.Snowflake
import discord4j.core.DiscordClient
import discord4j.core.event.domain.interaction.ApplicationCommandInteractionEvent
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.spec.AudioChannelJoinSpec
import discord4j.discordjson.json.ApplicationCommandRequest
import discord4j.gateway.intent.Intent
import discord4j.gateway.intent.IntentSet
import discord4j.voice.VoiceConnection
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.kyokobot.libdave.natives.DaveNativeBindings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// AudioReceiver é deprecated no Discord4J porque o Discord não oferece suporte oficial
// à recepção de áudio por bots. Não existe substituto na biblioteca.
@Suppress("DEPRECATION")
fun main(): Unit =
    runBlocking {
        configureDave()

        val botToken =
            Config.botToken
                ?: error("Defina a variável de ambiente BOT_TOKEN antes de iniciar a Kate.")

        val ollamaClient = OllamaClient()
        val vectorClient = VectorClient()
        val ragClient = RagClient(ollamaClient, vectorClient)
        val whisperClient =
            WhisperClient(
                modelPath = Config.whisperModelPath,
                whisperCliPath = Config.whisperCliPath,
            )
        val voiceAudioProvider = VoiceAudioProvider()
        val speakerService =
            Speaker(
                voiceModelPath = Config.piperVoicePath,
                outputDir = Config.soundsDir,
            )
        println("🔊 [TTS] Perfil jovem ativo: 48 kHz, pitch 1.10, length-scale 0.88, loudnorm -16 LUFS")

        val activeVoiceConnections = ConcurrentHashMap<Long, VoiceConnection>()
        val activeTextChannels = ConcurrentHashMap<Long, MessageChannel>()

        val gateway =
            DiscordClient
                .create(botToken)
                .gateway()
                .setEnabledIntents(IntentSet.nonPrivileged().or(IntentSet.of(Intent.MESSAGE_CONTENT)))
                .login()
                .awaitSingle()

        val joinCommand =
            ApplicationCommandRequest
                .builder()
                .name("join")
                .description("Faz a Kate entrar no seu canal de voz para trocar uma ideia")
                .build()

        val leaveCommand =
            ApplicationCommandRequest
                .builder()
                .name("leave")
                .description("Faz a Kate sair do seu canal de voz")
                .build()

        val discordClient = gateway.restClient
        val applicationId = discordClient.applicationId.awaitSingle()

        discordClient.applicationService
            .bulkOverwriteGlobalApplicationCommand(applicationId, listOf(joinCommand, leaveCommand))
            .doOnNext { println("✅ [Discord] Comandos registrados") }
            .then()
            .awaitSingleOrNull()

        gateway
            .on(MessageCreateEvent::class.java)
            .flatMap { event ->
                mono {
                    val collectionId = event.message.channelId.asString()
                    handleMessage(event, ollamaClient, vectorClient, ragClient, collectionId)
                }
            }.doOnError { it.printStackTrace() }
            .subscribe()

        gateway
            .on(ApplicationCommandInteractionEvent::class.java)
            .flatMap { event ->
                mono {
                    when {
                        event.commandName.equals("leave", ignoreCase = true) -> {
                            val guildId =
                                event.interaction.guildId
                                    .map { it.asLong() }
                                    .orElse(null)
                            if (guildId != null) {
                                activeVoiceConnections.remove(guildId)?.disconnect()?.subscribe()
                                activeTextChannels.remove(guildId)
                            }
                            voiceAudioProvider.clearQueue()
                            event.reply("Beleza, vou sair.").awaitSingleOrNull()
                        }

                        event.commandName.equals("join", ignoreCase = true) -> {
                            val guildId =
                                event.interaction.guildId
                                    .map { it.asLong() }
                                    .orElse(null)
                                    ?: return@mono
                            val member = event.user.asMember(Snowflake.of(guildId)).awaitSingle()
                            val voiceState = member.voiceState.awaitSingleOrNull()
                            val voiceChannel = voiceState?.channel?.awaitSingleOrNull()

                            if (voiceChannel == null) {
                                event
                                    .reply("Ei, você precisa estar conectado em um canal de voz para eu entrar!")
                                    .awaitSingleOrNull()
                                return@mono
                            }

                            event.interaction.channel.awaitSingleOrNull()?.let { channel ->
                                activeTextChannels[guildId] = channel
                            }

                            val collectionId = "voice_$guildId"
                            val voiceReceiver =
                                createVoiceReceiver(
                                    guildId = guildId,
                                    collectionId = collectionId,
                                    ollamaClient = ollamaClient,
                                    vectorClient = vectorClient,
                                    ragClient = ragClient,
                                    speakerService = speakerService,
                                    voiceAudioProvider = voiceAudioProvider,
                                    whisperClient = whisperClient,
                                    activeTextChannels = activeTextChannels,
                                )

                            val voiceConnection =
                                voiceChannel
                                    .join(
                                        AudioChannelJoinSpec
                                            .builder()
                                            .provider(voiceAudioProvider)
                                            .receiver(voiceReceiver)
                                            .selfDeaf(false)
                                            .selfMute(false)
                                            .build(),
                                    ).awaitSingle()

                            activeVoiceConnections[guildId] = voiceConnection

                            val introFile = File(Config.introSoundPath)
                            if (introFile.exists()) {
                                voiceAudioProvider.playFile(introFile)
                            }

                            event.reply("Opa! Tô na área ouvindo e pronta pra falar!").awaitSingleOrNull()
                        }
                    }
                }
            }.doOnError { it.printStackTrace() }
            .subscribe()

        gateway.onDisconnect().awaitSingle()
    }

private fun configureDave() {
    try {
        DaveNativeBindings.inst().daveSetLogSink { severity, file, line, message ->
            println("[DAVE][$severity] $file:$line $message")
        }
        val protocolVersion = DaveNativeBindings.inst().daveMaxSupportedProtocolVersion()
        println("✅ [DAVE] Log nativo habilitado, protocolo=$protocolVersion")
    } catch (t: Throwable) {
        println("❌ [DAVE] Falha ao inicializar libdave: ${t.message}")
        t.printStackTrace()
    }
}

private fun createVoiceReceiver(
    guildId: Long,
    collectionId: String,
    ollamaClient: OllamaClient,
    vectorClient: VectorClient,
    ragClient: RagClient,
    speakerService: Speaker,
    voiceAudioProvider: VoiceAudioProvider,
    whisperClient: WhisperClient,
    activeTextChannels: ConcurrentHashMap<Long, MessageChannel>,
): VoiceAudioReceiver =
    VoiceAudioReceiver(
        whisperClient = whisperClient,
        isBotSpeakingProvider = voiceAudioProvider::isPlaying,
    ) { _, userSpeech ->
        println("🎤 [VOZ] Detectado no canal de voz: $userSpeech")

        if (!WakeWordMatcher.isCalled(userSpeech)) {
            println("[DEBUG] Fala não chamou pela Kate. Ignorando.")
            return@VoiceAudioReceiver
        }

        try {
            val context = vectorClient.getChatDocument(collectionId)?.first

            println("🧠 [VOZ] Gerando resposta da Kate para: $userSpeech")
            val ragContext = ragClient.retrieve(userSpeech, ResponseMode.VOICE)
            val response =
                ollamaClient.generateResponse(
                    prompt = userSpeech,
                    context = context,
                    knowledgeContext = ragContext?.prompt,
                    mode = ResponseMode.VOICE,
                )
            val displayResponse = decorateResponse(response, ragContext, ResponseMode.VOICE)
            println("🗣️ [VOZ] Resposta da Kate: $response")

            saveAndProcessHistory(collectionId, "Usuário", userSpeech, ollamaClient, vectorClient)
            saveAndProcessHistory(collectionId, "Kate", response, ollamaClient, vectorClient)

            activeTextChannels[guildId]?.createMessage("🎙️ **Kate:** $displayResponse")?.subscribe()

            val speechFile =
                speakerService.generateSpeech(
                    displayResponse,
                    "kate_voice_${System.currentTimeMillis()}.wav",
                )
            if (speechFile != null) {
                voiceAudioProvider.playFile(speechFile)
            } else {
                println("❌ Falha ao sintetizar voz da Kate")
            }
        } catch (e: Exception) {
            println("❌ Erro ao responder por voz: ${e.message}")
            e.printStackTrace()
        }
    }

suspend fun handleMessage(
    event: MessageCreateEvent,
    ollamaClient: OllamaClient,
    vectorClient: VectorClient,
    ragClient: RagClient,
    collectionId: String,
) {
    val content = event.message.content
    val channel = event.message.channel.awaitSingle()
    val author = event.message.author.orElse(null) ?: return
    if (author.isBot) return

    val isKateCalled = WakeWordMatcher.isCalled(content)

    val context =
        if (isKateCalled) {
            vectorClient.getChatDocument(collectionId)?.first.also { chatContext ->
                if (chatContext != null) {
                    println("[DEBUG] Contexto único recuperado: ${chatContext.take(100)}...")
                }
            }
        } else {
            null
        }

    var response: String? = null
    var displayResponse: String? = null
    if (isKateCalled) {
        val ragContext = ragClient.retrieve(content, ResponseMode.TEXT)
        response =
            ollamaClient.generateResponse(
                prompt = content,
                context = context,
                knowledgeContext = ragContext?.prompt,
                mode = ResponseMode.TEXT,
            )
        displayResponse = decorateResponse(response, ragContext, ResponseMode.TEXT)
    }

    if (displayResponse != null) {
        channel.createMessage(displayResponse).awaitSingle()
        println("[DEBUG] Respondendo $content com $response")
    }

    saveAndProcessHistory(collectionId, author.username, content, ollamaClient, vectorClient)
    if (response != null) {
        saveAndProcessHistory(collectionId, "Kate", response, ollamaClient, vectorClient)
    }
}

fun decorateResponse(
    response: String,
    ragContext: RagContext?,
    mode: ResponseMode,
): String {
    if (ragContext == null) return response

    var text = response.trim()
    if (ragContext.hasSpoiler && !text.startsWith("⚠️") && !text.lowercase().contains("spoiler")) {
        text =
            if (mode == ResponseMode.VOICE) {
                "Aviso de spoiler. $text"
            } else {
                "⚠️ **Aviso de spoiler:** o conteúdo abaixo pode revelar chefes, história ou finais.\n\n$text"
            }
    }

    if (mode == ResponseMode.TEXT && ragContext.sources.isNotEmpty()) {
        val header = if (ragContext.fromWeb) "🌐 **Fontes (web):**" else "📚 **Fontes:**"
        val block = "\n\n$header\n${ragContext.sourcesText()}"
        val budget = MAX_DISCORD_MESSAGE_LENGTH - block.length
        if (budget > 0) {
            if (text.length > budget) text = text.take(budget - 1) + "…"
            text += block
        } else {
            text = text.take(MAX_DISCORD_MESSAGE_LENGTH - 1) + "…"
        }
    }

    return text
}

suspend fun saveAndProcessHistory(
    collectionId: String,
    userName: String,
    messageContent: String,
    ollamaClient: OllamaClient,
    vectorClient: VectorClient,
) {
    val chatData = vectorClient.getChatDocument(collectionId)
    val metadata = chatData?.second

    var summary = metadata?.get("summary")?.jsonPrimitive?.content ?: ""
    val messages =
        metadata
            ?.get("messages")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.toMutableList()
            ?: mutableListOf()

    messages.add("$userName: $messageContent")

    if (messages.size > MAX_HISTORY_MESSAGES) {
        val oldest = messages.removeAt(0)
        println("[DEBUG] Sumarizando mensagem antiga: ${oldest.take(30)}...")
        summary = ollamaClient.updateSummary(summary.ifBlank { null }, oldest)
    }

    val newDocument =
        buildString {
            if (summary.isNotBlank()) {
                append("Resumo da conversa anterior: $summary\n\n")
            }
            append("Mensagens recentes:\n")
            append(messages.joinToString("\n"))
        }

    val docEmbed = ollamaClient.getEmbeddings(newDocument) ?: return

    val newMetadata =
        buildJsonObject {
            put("summary", summary)
            put("messages", JsonArray(messages.map { JsonPrimitive(it) }))
        }

    vectorClient.addDocument(
        collectionId = collectionId,
        id = collectionId,
        embedding = docEmbed,
        document = newDocument,
        metadata = newMetadata,
    )
    println("[DEBUG] Estado do chat atualizado no documento único.")
}

private const val MAX_DISCORD_MESSAGE_LENGTH = 2000
private const val MAX_HISTORY_MESSAGES = 19
