package dev.notnull

object Config {
    val botToken: String?
        get() = envOrNull("BOT_TOKEN")

    val databaseUrl: String
        get() = env("KATE_DB_URL", "jdbc:postgresql://localhost:5432/kate")

    val databaseUser: String
        get() = env("KATE_DB_USER", "kate")

    val databasePassword: String
        get() = env("KATE_DB_PASSWORD", "kate")

    val ollamaUrl: String
        get() = env("OLLAMA_URL", "http://localhost:11434")

    val ollamaModel: String
        get() = env("OLLAMA_MODEL", "kate")

    val ollamaSummaryModel: String
        get() = env("OLLAMA_SUMMARY_MODEL", "gemma4:e4b")

    val ollamaEmbeddingModel: String
        get() = env("OLLAMA_EMBEDDING_MODEL", "embeddinggemma:latest")

    val searxngUrl: String
        get() = env("SEARXNG_URL", "http://localhost:8080")

    val whisperModelPath: String
        get() = env("WHISPER_MODEL_PATH", "models/ggml-base.bin")

    val whisperCliPath: String
        get() = env("WHISPER_CLI_PATH", "/usr/bin/whisper-cli")

    val piperVoicePath: String
        get() = env("PIPER_VOICE_PATH", "src/main/resources/voices/pt_BR-dii-high.onnx")

    val soundsDir: String
        get() = env("KATE_SOUNDS_DIR", "lavalink/sounds")

    val introSoundPath: String
        get() = env("KATE_INTRO_SOUND", "$soundsDir/voce-nao-tem-aura.mp3")

    private fun env(
        name: String,
        default: String,
    ): String = envOrNull(name) ?: default

    private fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
