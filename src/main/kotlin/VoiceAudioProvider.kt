package dev.notnull

import discord4j.voice.AudioProvider
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class VoiceAudioProvider : AudioProvider(ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)) {
    private val frameQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isCurrentlyPlaying = AtomicBoolean(false)
    private val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isPlaying(): Boolean = isCurrentlyPlaying.get() || frameQueue.isNotEmpty()

    fun clearQueue() {
        frameQueue.clear()
        isCurrentlyPlaying.set(false)
    }

    fun playFile(audioFile: File) {
        if (!audioFile.exists()) {
            println("❌ Arquivo de áudio não encontrado: ${audioFile.absolutePath}")
            return
        }

        scope.launch {
            isCurrentlyPlaying.set(true)
            try {
                // Converte qualquer formato de áudio para PCM estéreo 16-bit 48 kHz via ffmpeg.
                val process =
                    ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-i",
                        audioFile.absolutePath,
                        "-f",
                        "s16le",
                        "-ar",
                        SAMPLE_RATE.toString(),
                        "-ac",
                        CHANNELS.toString(),
                        "pipe:1",
                    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

                val pcmBuffer = ByteArray(FRAME_SAMPLES * CHANNELS * 2)
                val opusBuffer = ByteArray(MAX_OPUS_PACKET_SIZE)
                val input: InputStream = process.inputStream

                while (true) {
                    var offset = 0
                    while (offset < pcmBuffer.size) {
                        val read = input.read(pcmBuffer, offset, pcmBuffer.size - offset)
                        if (read == -1) break
                        offset += read
                    }

                    if (offset == 0) break
                    if (offset < pcmBuffer.size) pcmBuffer.fill(0, offset, pcmBuffer.size)

                    synchronized(encoder) {
                        val encodedLength =
                            encoder.encode(
                                pcmBuffer,
                                0,
                                FRAME_SAMPLES,
                                opusBuffer,
                                0,
                                opusBuffer.size,
                            )
                        if (encodedLength > 0) {
                            frameQueue.offer(opusBuffer.copyOf(encodedLength))
                        }
                    }
                }

                process.waitFor()
            } catch (e: Exception) {
                println("❌ Erro ao reproduzir áudio: ${e.message}")
            } finally {
                while (frameQueue.isNotEmpty()) {
                    delay(50.milliseconds)
                }
                isCurrentlyPlaying.set(false)
            }
        }
    }

    override fun provide(): Boolean {
        val frame = frameQueue.poll() ?: return false
        val target = buffer
        target.clear()
        target.put(frame)
        target.flip()
        return true
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FRAME_SAMPLES = 960
        const val MAX_OPUS_PACKET_SIZE = 1275
    }
}
