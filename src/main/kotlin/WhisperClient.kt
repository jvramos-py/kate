package dev.notnull

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperClient(
    private val modelPath: String = Config.whisperModelPath,
    private val whisperCliPath: String = Config.whisperCliPath,
    private val language: String = "pt",
    private val initialPrompt: String = "Nomes: Kate, Ana, João.",
) {
    suspend fun transcribe(audioFile: File): String? =
        withContext(Dispatchers.IO) {
            if (!audioFile.exists() || audioFile.length() < MIN_AUDIO_FILE_BYTES) {
                return@withContext null
            }

            try {
                val command =
                    mutableListOf(
                        whisperCliPath,
                        "-m",
                        modelPath,
                        "-l",
                        language,
                        "-nt",
                    )
                if (initialPrompt.isNotBlank()) {
                    command += listOf("--prompt", initialPrompt)
                }
                command += audioFile.absolutePath

                val process = ProcessBuilder(command).redirectErrorStream(true).start()

                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                if (!finished) {
                    process.destroyForcibly()
                    return@withContext null
                }

                val result =
                    output
                        .lines()
                        .map { it.trim() }
                        .filter { line ->
                            line.isNotEmpty() &&
                                IGNORED_OUTPUT_PREFIXES.none { line.startsWith(it) }
                        }.joinToString(" ")
                        .trim()

                if (result.isNotBlank()) result else null
            } catch (e: Exception) {
                println("❌ Erro no Whisper: ${e.message}")
                null
            }
        }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 30L
        const val MIN_AUDIO_FILE_BYTES = 1_000L
        val IGNORED_OUTPUT_PREFIXES =
            listOf(
                "load_backend:",
                "whisper_",
                "system_info:",
                "read_audio_data:",
                "main:",
            )
    }
}
