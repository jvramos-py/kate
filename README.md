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

**Com Docker (recomendado):** apenas Docker + Docker Compose. A imagem já inclui JDK 21,
Ollama, Piper, FFmpeg e `whisper-cli`.

**Sem Docker:**

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
   docker compose up -d postgres
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

## Docker

O `Dockerfile` empacota o bot com Piper, FFmpeg e `whisper-cli`. Os modelos grandes
(`models/` e `src/main/resources/voices/`) são montados do host.

1. Copie o `.env` e preencha o token:

   ```bash
   cp .env.example .env
   # edite BOT_TOKEN
   ```

### Opção A — stack completa (tudo em container)

Sobe Postgres, Ollama, SearXNG e o bot:

```bash
docker compose up -d --build
docker compose logs -f kate
```

O serviço `ollama-models` baixa `gemma4:e4b`, cria o modelo `kate` a partir do
`kate.mf` e baixa o modelo de embeddings. A primeira execução pode demorar
bastante por causa do download dos modelos (~10 GB).

GPU (apenas na opção A):

```bash
# AMD (ROCm) — requer amdgpu carregado e /dev/kfd + /dev/dri
docker compose -f docker-compose.yml -f docker-compose.gpu-amd.yml up -d

# NVIDIA — requer driver NVIDIA + NVIDIA Container Toolkit
docker compose -f docker-compose.yml -f docker-compose.gpu-nvidia.yml up -d
```

Para placas AMD fora da lista oficial do ROCm, defina `HSA_OVERRIDE_GFX_VERSION`
(ex.: `10.3.0`).

> Se você já tinha o compose antigo em `lavalink/`, remova os containers antes
> (`docker rm -f postgres kate-lavalink`) e, para reaproveitar os dados do RAG,
> defina `POSTGRES_VOLUME=compose-amd_postgres_data` no `.env`.

### Opção B — reaproveitar serviços do host

Se o Ollama, o Postgres e o SearXNG já rodam na máquina (fora do Docker), suba só o
bot e o bootstrap de modelos:

```bash
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --build
```

O `docker-compose.host.yml` aponta `OLLAMA_URL`, `KATE_DB_URL` e `SEARXNG_URL` para
`host.docker.internal` e desativa as cópias em container. Requer que os serviços
escutem em `0.0.0.0` (não apenas `127.0.0.1`).

O SearXNG é opcional nos dois modos: sem ele o bot continua funcionando, apenas sem
busca na web.

## Ingestão da base de conhecimento

A ingestão roda do host (a porta `5432` do Postgres é publicada justamente para isso).
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
lavalink/sounds/          Pasta de áudio (TTS gerado e som de introdução)
models/                   Modelos do whisper.cpp (não versionados)
searxng/                  settings.yml usado pelo container do SearXNG
Dockerfile                Imagem do bot (JRE 21 + Piper + FFmpeg + whisper-cli)
docker-compose*.yml       Stack completa (Postgres, Ollama, SearXNG, bot)
```

## Observações

- O `AudioReceiver` do Discord4J é marcado como deprecated porque o Discord não
  oferece suporte oficial à recepção de áudio por bots. A dependência é mantida
  de forma intencional e isolada em `VoiceAudioReceiver`.
- O `PacketTransformer` local corrige o tratamento de padding RTP/DAVE antes de
  entregar o áudio ao decodificador Opus.
- Nunca faça commit do `.env` nem de modelos grandes; ambos já estão no
  `.gitignore`.
