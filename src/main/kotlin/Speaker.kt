package dev.notnull

import java.io.File
import java.util.concurrent.TimeUnit

class Speaker(
    private val voiceModelPath: String = Config.piperVoicePath,
    private val outputDir: String = Config.soundsDir,
    pitchFactor: Double = 1.10,
    private val lengthScale: Double = 0.88,
) {
    private val ffmpegFilter =
        listOf(
            "highpass=f=90",
            "rubberband=pitch=$pitchFactor:formant=shifted:pitchq=quality",
            "equalizer=f=350:t=q:w=1:g=-2",
            "equalizer=f=4000:t=q:w=1.2:g=2.5",
            "treble=g=2.5:f=6500",
            "acompressor=threshold=-18dB:ratio=3:attack=5:release=80:makeup=3",
            "loudnorm=I=-16:TP=-1.5:LRA=11:linear=false",
            "aresample=48000",
        ).joinToString(",")

    fun generateSpeech(
        text: String,
        fileName: String,
    ): File? {
        val directory = File(outputDir).apply { if (!exists()) mkdirs() }
        val outputFile = File(directory, fileName)
        val cleanText = SpeechText.sanitize(text)
        if (cleanText.isEmpty()) return null

        return try {
            val piper =
                ProcessBuilder(
                    "piper-tts",
                    "-m",
                    voiceModelPath,
                    "--length-scale",
                    lengthScale.toString(),
                    "--output_raw",
                ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

            val ffmpeg =
                ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-f",
                    "s16le",
                    "-ar",
                    PIPER_SAMPLE_RATE.toString(),
                    "-ac",
                    "1",
                    "-i",
                    "pipe:0",
                    "-af",
                    ffmpegFilter,
                    outputFile.absolutePath,
                ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

            val writer =
                Thread {
                    piper.outputStream.use { it.write(cleanText.toByteArray(Charsets.UTF_8)) }
                }
            writer.start()

            piper.inputStream.use { input ->
                ffmpeg.outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            writer.join()

            val piperFinished = piper.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val ffmpegFinished = ffmpeg.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!piperFinished) piper.destroyForcibly()
            if (!ffmpegFinished) ffmpeg.destroyForcibly()

            if (piperFinished && ffmpegFinished && ffmpeg.exitValue() == 0 && outputFile.exists()) {
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            println("❌ Erro no TTS da Kate: ${e.message}")
            null
        }
    }

    private companion object {
        const val PIPER_SAMPLE_RATE = 22_050
        const val PROCESS_TIMEOUT_SECONDS = 20L
    }
}
