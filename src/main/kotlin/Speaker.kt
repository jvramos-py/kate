package dev.notnull

import java.io.File
import java.util.concurrent.TimeUnit

class Speaker(
    private val voiceModelPath: String,
    private val outputDir: String = "/opt/lavalink/sounds/"
) {
    val pitchFactor = 1.15
    val tempoCorrection = 1.0 / pitchFactor

    val ffmpegFilter = "asetrate=22050*$pitchFactor,atempo=$tempoCorrection,aresample=22050"

    fun generateSpeech(text: String, fileName: String): File? {
        val outputFile = File(outputDir, fileName)

        val cleanText = text.replace("\"", "").replace("'", "")

        try {
            val command = "echo \"$cleanText\" | piper-tts -m $voiceModelPath --output_raw | " +
                    "ffmpeg -y -f s16le -ar 22050 -ac 1 -i - " +
                    "-af $ffmpegFilter " +
                    "${outputFile.absolutePath}"

            val process = ProcessBuilder("sh", "-c", command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            val finished = process.waitFor(10, TimeUnit.SECONDS)

            return if (finished && outputFile.exists()) outputFile else null
        } catch (e: Exception) {
            println("❌ Erro no TTS da Kate: ${e.message}")
            return null
        }
    }
}