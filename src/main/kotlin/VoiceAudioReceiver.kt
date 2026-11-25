@file:Suppress("DEPRECATION") // AudioReceiver é deprecated no Discord4J e não possui substituto.

package dev.notnull

import discord4j.voice.AudioReceiver
import io.github.jaredmdobson.concentus.OpusDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class VoiceAudioReceiver(
    private val whisperClient: WhisperClient,
    private val isBotSpeakingProvider: () -> Boolean,
    private val onSpeechRecognized: suspend (ssrc: Int, text: String) -> Unit,
) : AudioReceiver() {
    private class SpeakerSession {
        val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)
        val pcm16Stream = ByteArrayOutputStream()
        val lastVoiceTime = AtomicLong(0)
        val hasSpeech = AtomicBoolean(false)
        val pcmStereoBuffer = ShortArray(DECODED_SAMPLES_PER_PACKET * CHANNELS)
        val packetCount = AtomicLong(0)
        val decodeErrors = AtomicLong(0)
        val totalDecodedSamples = AtomicLong(0)
        val maxRms = AtomicLong(0)
        val loggedTrailerStrip = AtomicBoolean(false)
    }

    private val sessions = ConcurrentHashMap<Int, SpeakerSession>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                delay(SILENCE_CHECK_INTERVAL_MS.milliseconds)
                flushFinishedPhrases()
            }
        }
    }

    private fun flushFinishedPhrases() {
        val now = System.currentTimeMillis()

        for ((ssrc, session) in sessions) {
            val lastSpoke = session.lastVoiceTime.get()
            if (!session.hasSpeech.get() || now - lastSpoke <= SILENCE_TIMEOUT_MS) continue

            val pcmBytes: ByteArray
            synchronized(session) {
                session.hasSpeech.set(false)
                pcmBytes = session.pcm16Stream.toByteArray()
                session.pcm16Stream.reset()
            }

            if (pcmBytes.size >= MIN_SPEECH_BYTES) {
                println("🎧 [VOZ] Fim de frase do ssrc=$ssrc (${pcmBytes.size} bytes), transcrevendo...")
                processAudioPhrase(ssrc, pcmBytes)
            } else {
                println("🎧 [VOZ] Fala curta ignorada do ssrc=$ssrc (${pcmBytes.size} bytes)")
            }
        }
    }

    private fun processAudioPhrase(
        ssrc: Int,
        pcmBytes: ByteArray,
    ) {
        scope.launch {
            val tempWav = Files.createTempFile("kate_voice_input_${ssrc}_", ".wav").toFile()
            try {
                writeWavFile(tempWav, pcmBytes, SAMPLE_RATE, 1)
                val transcription = whisperClient.transcribe(tempWav)
                println("📝 [WHISPER] ssrc=$ssrc transcreveu: ${transcription ?: "<vazio>"}")
                if (!transcription.isNullOrBlank()) {
                    onSpeechRecognized(ssrc, transcription)
                }
            } catch (e: Exception) {
                println("❌ Erro ao processar frase de voz: ${e.message}")
            } finally {
                tempWav.delete()
            }
        }
    }

    override fun receive(
        sequence: Char,
        timestamp: Int,
        ssrc: Int,
        audio: ByteArray,
    ) {
        // Se a Kate estiver falando, ignora o áudio recebido para não transcrever a si mesma.
        if (isBotSpeakingProvider()) return

        // Ignora pacote de silêncio padrão do Discord (0xF8, 0xFF, 0xFE).
        if (audio.size == SILENCE_PACKET_SIZE && audio.contentEquals(SILENCE_PACKET)) return

        val session = sessions.computeIfAbsent(ssrc) { SpeakerSession() }

        val packetNumber = session.packetCount.incrementAndGet()
        if (packetNumber == 1L) {
            println("🔊 [RX] Primeiro pacote recebido do ssrc=$ssrc (${audio.size} bytes)")
        } else if (packetNumber % 100L == 0L) {
            println(
                "🔊 [RX] ssrc=$ssrc pacotes=$packetNumber amostras=${session.totalDecodedSamples.get()} " +
                    "maxRms=${session.maxRms.get()} erros=${session.decodeErrors.get()}",
            )
        }

        synchronized(session) {
            try {
                val samplesDecoded = decodeOpus(session, audio)
                if (samplesDecoded <= 0) return

                session.totalDecodedSamples.addAndGet(samplesDecoded.toLong())

                val totalSamples = samplesDecoded * CHANNELS
                var sumSquares = 0.0
                for (i in 0 until totalSamples) {
                    val sample = session.pcmStereoBuffer[i].toDouble()
                    sumSquares += sample * sample
                }
                val rms = sqrt(sumSquares / totalSamples)
                session.maxRms.accumulateAndGet(rms.toLong()) { current, value -> maxOf(current, value) }

                // Downsample de 48 kHz estéreo para 16 kHz mono (48000 / 16000 = 3).
                val downsampledSize = samplesDecoded / DOWNSAMPLE_FACTOR
                val monoBytes = ByteArray(downsampledSize * 2)
                val buffer = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until downsampledSize) {
                    val originalIndex = i * DOWNSAMPLE_FACTOR * CHANNELS
                    val left = session.pcmStereoBuffer[originalIndex]
                    val right = session.pcmStereoBuffer[originalIndex + 1]
                    buffer.putShort(((left + right) / 2).toShort())
                }

                session.pcm16Stream.write(monoBytes)

                if (rms > VOICE_RMS_THRESHOLD) {
                    session.hasSpeech.set(true)
                    session.lastVoiceTime.set(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                val errors = session.decodeErrors.incrementAndGet()
                if (errors <= MAX_LOGGED_DECODE_ERRORS) {
                    println(
                        "⚠️ [RX] Erro ao decodificar Opus do ssrc=$ssrc: ${e.message} " +
                            "size=${audio.size} head=${toHex(audio, 0, 8)} " +
                            "tail=${toHex(audio, audio.size - 24, audio.size)}",
                    )
                }
            }
        }
    }

    private fun decodeOpus(
        session: SpeakerSession,
        payload: ByteArray,
    ): Int {
        var lastError: Exception? = null

        for ((index, candidate) in buildCandidates(payload).withIndex()) {
            try {
                val decoded =
                    session.decoder.decode(
                        candidate,
                        0,
                        candidate.size,
                        session.pcmStereoBuffer,
                        0,
                        DECODED_SAMPLES_PER_PACKET,
                        false,
                    )
                if (decoded > 0) {
                    if (index > 0 && session.loggedTrailerStrip.compareAndSet(false, true)) {
                        println(
                            "🔧 [RX] Removido trailer DAVE/padding do pacote Opus " +
                                "(${payload.size} -> ${candidate.size} bytes)",
                        )
                    }
                    return decoded
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("Pacote Opus vazio ou inválido")
    }

    private fun buildCandidates(payload: ByteArray): List<ByteArray> {
        val candidates = ArrayList<ByteArray>(4)
        candidates.add(payload)

        // Alguns quadros chegam em modo passthrough do DAVE com trailers:
        // [opus bruto][bloco suplementar DAVE][padding RTP].
        val withoutPadding = stripRtpPadding(payload)
        if (withoutPadding != null) candidates.add(withoutPadding)

        val withoutSupplemental = stripDaveSupplemental(payload)
        if (withoutSupplemental != null) candidates.add(withoutSupplemental)

        if (withoutPadding != null) {
            stripDaveSupplemental(withoutPadding)?.let { candidates.add(it) }
        }

        return candidates
    }

    private fun stripRtpPadding(payload: ByteArray): ByteArray? {
        if (payload.isEmpty()) return null
        val padding = payload[payload.size - 1].toInt() and 0xFF
        return if (padding in 1 until payload.size) {
            payload.copyOf(payload.size - padding)
        } else {
            null
        }
    }

    private fun stripDaveSupplemental(payload: ByteArray): ByteArray? {
        if (payload.size < 12) return null
        val magic1 = payload[payload.size - 2].toInt() and 0xFF
        val magic2 = payload[payload.size - 1].toInt() and 0xFF
        if (magic1 != 0xFA || magic2 != 0xFA) return null

        val supplementalSize = payload[payload.size - 3].toInt() and 0xFF
        return if (supplementalSize in 11 until payload.size) {
            payload.copyOf(payload.size - supplementalSize)
        } else {
            null
        }
    }

    private fun toHex(
        data: ByteArray,
        from: Int,
        to: Int,
    ): String {
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(data.size)
        if (start >= end) return ""
        return (start until end).joinToString(" ") { data[it].toUByte().toString(16).padStart(2, '0') }
    }

    private fun writeWavFile(
        file: File,
        pcm16Data: ByteArray,
        sampleRate: Int,
        channels: Int,
    ) {
        val totalAudioLen = pcm16Data.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen.toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1.toShort()) // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * 2).toShort()) // BlockAlign
        buffer.putShort(16.toShort()) // BitsPerSample
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen.toInt())

        file.outputStream().use { output ->
            output.write(header)
            output.write(pcm16Data)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val DECODED_SAMPLES_PER_PACKET = 960
        const val DOWNSAMPLE_FACTOR = 3
        const val MIN_SPEECH_BYTES = 19_200
        const val SILENCE_TIMEOUT_MS = 1_200L
        const val SILENCE_CHECK_INTERVAL_MS = 100L
        const val VOICE_RMS_THRESHOLD = 200.0
        const val MAX_LOGGED_DECODE_ERRORS = 5L
        const val SILENCE_PACKET_SIZE = 3
        val SILENCE_PACKET = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
    }
}
