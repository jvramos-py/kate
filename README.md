# Kate Kitty

Bot de Discord com personalidade própria que conversa por texto e por voz, usa RAG sobre wikis de jogos, responde com Ollama e sintetiza a fala com Piper TTS.

## Funcionalidades

- Conversa em canais de texto quando é chamada pelo nome.
- Entra em canais de voz com `/join` e sai com `/leave`.
- Transcreve falas com `whisper.cpp` e responde em voz com Piper + FFmpeg.
- Mantém um histórico resumido por canal/conversa no PostgreSQL.
- Recupera contexto local com pgvector e, opcionalmente, complementa com SearXNG.
- Base de conhecimento de Dark Souls, Elden Ring e Helldivers 2.

## Requisitos

- JDK 21.
- PostgreSQL com a extensão `pgvector`.
- [Ollama](https://ollama.com/) com os modelos configurados.
- [Piper TTS](https://github.com/rhasspy/piper) (`piper-tts` no `PATH`).
- FFmpeg com o filtro `rubberband`.
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) (`whisper-cli`).
- Opcional: [SearXNG](https://docs.searxng.org/) para busca na web.

## Configuração

1. Instale as dependências do sistema e copie o arquivo de exemplo:

   ```bash
   cp .env.example .env
   ```

2. Preencha o `BOT_TOKEN` e ajuste os caminhos/serviços se necessário. O Gradle não carrega o `.env` automaticamente; exporte as variáveis antes de rodar:

   ```bash
   set -a
   source .env
   set +a
   ```

3. Baixe os modelos que não ficam versionados no repositório:

   - Piper: coloque `pt_BR-dii-high.onnx` e `pt_BR-dii-high.onnx.json` em
     `src/main/resources/voices/`.
   - Whisper: coloque `ggml-base.bin` em `models/`.

4. Suba o PostgreSQL com pgvector:

   ```bash
   docker compose -f lavalink/compose-amd/docker-compose.yml up -d postgres
   ```

   As credenciais padrão são `kate` / `kate` / banco `kate`. Para usar outros valores,
   ajuste `KATE_DB_URL`, `KATE_DB_USER` e `KATE_DB_PASSWORD`.

5. Crie o modelo principal no Ollama:

   ```bash
   ollama create kate -f kate.mf
   ollama pull embeddinggemma
   ollama pull gemma4:e4b
   ```

6. Rode a aplicação:

   ```bash
   ./gradlew run
   ```

## Ingestão da base de conhecimento

O comando abaixo baixa páginas da Fandom, gera chunks e embeddings e grava tudo no PostgreSQL:

```bash
./gradlew ingestRag --args="--all"
```

Opções disponíveis:

```bash
./gradlew ingestRag --args="helldivers2 eldenring --limit=200 --force"
```

## Qualidade e build

```bash
./gradlew build
./gradlew ktlintCheck
```

O projeto usa `ktlint` no build para manter o estilo do código.

## Estrutura

```text
src/main/kotlin/          Código principal do bot, RAG, voz e clientes HTTP
src/main/java/            Patch local do PacketTransformer do Discord4J
src/main/resources/       Logback e modelos de voz (não versionados)
lavalink/                 Configurações opcionais de infraestrutura e pasta de áudio
models/                   Modelos do whisper.cpp (não versionados)
```

## Observações

- O `AudioReceiver` do Discord4J é marcado como deprecated porque o Discord não
  oferece suporte oficial à recepção de áudio por bots. A dependência é mantida
  de forma intencional e isolada em `VoiceAudioReceiver`.
- O `PacketTransformer` local corrige o tratamento de padding RTP/DAVE antes de
  entregar o áudio ao decodificador Opus.
- Nunca faça commit do `.env` nem de modelos grandes; ambos já estão no
  `.gitignore`.
