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

Se você já tem os modelos em um Ollama local (`~/.ollama/models`), importe-os para
o volume da stack e pule o download:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu-amd.yml \
               -f docker-compose.models-local.yml up -d --build
```

O serviço `ollama-import` copia o store local (montado somente para leitura) para o
volume `kate_ollama_data` e não faz nada se o volume já estiver populado. Aponte
`OLLAMA_MODELS_DIR` no `.env` para usar outro diretório (o padrão é
`~/.ollama/models`).

GPU (apenas na opção A):

```bash
# AMD — Vulkan (recomendado; sem configuração por arquitetura)
docker compose -f docker-compose.yml -f docker-compose.gpu-amd.yml up -d

# AMD — ROCm (opcional; veja a comparação abaixo)
./scripts/amd-gpu.sh --write
docker compose -f docker-compose.yml -f docker-compose.gpu-amd-rocm.yml up -d

# NVIDIA — requer driver NVIDIA + NVIDIA Container Toolkit
docker compose -f docker-compose.yml -f docker-compose.gpu-nvidia.yml up -d
```

### AMD: Vulkan ou ROCm?

O caminho padrão usa **Vulkan** (`OLLAMA_VULKAN=1` + `/dev/dri`). Ele descobre a GPU
pelo driver Mesa e **não precisa de `HSA_OVERRIDE_GFX_VERSION`**, então RDNA1, RDNA2,
RDNA3 e RDNA4 usam exatamente a mesma configuração.

| | Vulkan | ROCm |
|---|---|---|
| Configuração | nenhuma | `HSA_OVERRIDE_GFX_VERSION` depende da arquitetura |
| Compatibilidade | RDNA1–4, Vega, Polaris e iGPU | lista oficial do ROCm |
| Desempenho | bom | geralmente melhor em placas suportadas |
| Maturidade | backend mais novo no Ollama | backend mais maduro em cargas longas |

**Use ROCm quando:**

- sua placa está na lista oficial do ROCm (ex.: RX 6800/6900 = `gfx1030`,
  RX 7900 = `gfx1100`, MI200 = `gfx90a`) — aí não há override e o desempenho
  tende a ser maior;
- você roda modelos grandes e contextos longos e quer o melhor *prompt processing*;
- o bot fica ligado 24/7 e você prefere o backend mais maduro.

**Prefira Vulkan quando:**

- sua placa é `gfx1031`/`gfx1032`/`gfx1034` (RX 6600/6650/6700), RDNA1 ou RDNA4 recente;
- você usa iGPU;
- quer zero configuração ou teve erros/instabilidade com o override do ROCm.

Rode `./scripts/amd-gpu.sh` para ver o `gfx` da sua placa e a recomendação:

```text
GPU: AMD Radeon RX 6650 XT (RADV NAVI23)
gfx: gfx1032 (RDNA2)

ROCm: HSA_OVERRIDE_GFX_VERSION=10.3.0

Vulkan (recomendado, sem configuração):
  docker compose -f docker-compose.yml -f docker-compose.gpu-amd.yml up -d
```

Com `--write`, o valor é gravado no `.env` (ou removido, se a GPU for nativa).

> No **modo host** (opção B) quem escolhe o backend é o Ollama do host. O pacote
> oficial para Linux traz apenas ROCm, então o Vulkan não está disponível lá.

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

### Solução de problemas

**`Bind for 0.0.0.0:5432 failed: port is already allocated`**
Já existe um Postgres usando a porta `5432` (por exemplo, o container `postgres` da
stack antiga). Use a opção B ou remova o container antigo antes de subir a stack
completa:

```bash
docker rm -f postgres
# e, para reaproveitar os dados: POSTGRES_VOLUME=compose-amd_postgres_data no .env
```

**`ollama-models` falha com `dial tcp ...:11434: i/o timeout`**
O Ollama do host não está rodando (na opção B), não escuta em `0.0.0.0` ou o firewall
está bloqueando o tráfego vindo do Docker. Verifique:

```bash
curl -s http://localhost:11434/api/tags
sudo systemctl enable --now ollama
# se o firewall (ufw) estiver ativo, libere as portas para as redes do Docker:
sudo ufw allow from 172.16.0.0/12 to any port 11434 proto tcp
sudo ufw allow from 172.16.0.0/12 to any port 8080 proto tcp
```

**`kate` fica reiniciando**
Veja os logs (`docker compose logs kate`). As causas mais comuns são `BOT_TOKEN`
inválido, Postgres inacessível ou modelos ausentes no Ollama.

**`401: Unauthorized` mesmo com o token correto no `.env`**
Se `BOT_TOKEN` estiver **exportado no seu shell**, ele tem precedência sobre o `.env`
na interpolação do Compose. Confirme e rode sem ele:

```bash
printenv BOT_TOKEN >/dev/null && echo "exportado no shell"
unset BOT_TOKEN
```

**A GPU não está sendo usada**
Confirme qual backend o Ollama escolheu:

```bash
docker compose logs ollama | grep "inference compute"
docker compose exec ollama ollama ps
```

O esperado é `library=Vulkan` (AMD) ou `library=ROCm`, e `100% GPU` na saída do
`ollama ps`. Se aparecer apenas CPU, revise `devices` e os grupos do usuário no host
(`video`, `render`).

## Ingestão da base de conhecimento

A ingestão roda do host (a porta `5432` do Postgres é publicada justamente para isso).
O comando abaixo baixa páginas da Fandom, gera chunks e embeddings e grava tudo no PostgreSQL:

```bash
./gradlew ingestRag --args="--all"
```

Opções disponíveis:

- sem `--limit`: ingere **todas** as páginas, sem teto de quantidade nem de tempo;
- `--limit=N`: limita a N páginas por jogo (útil para testar uma wiki);
- `--resume`: salva e retoma o índice da última página processada;
- `--minutes=N`: encerra a execução após N minutos (padrão **30** quando `--resume` é usado; `0` = sem limite);
- `--force`: reingere tudo e zera o checkpoint do jogo.

```bash
./gradlew ingestRag --args="helldivers2 eldenring --limit=200 --force"
```

Jogos disponíveis (chave → wiki Fandom):

| Chave | Jogo | Wiki |
| --- | --- | --- |
| `helldivers2` | Helldivers 2 | `helldivers` |
| `darksouls1` | Dark Souls | `darksouls` |
| `darksouls2` | Dark Souls II | `darksouls` |
| `darksouls3` | Dark Souls III | `darksouls` |
| `eldenring` | Elden Ring | `eldenring` |
| `sekiro` | Sekiro: Shadows Die Twice | `sekiro-shadows-die-twice` |
| `bloodborne` | Bloodborne | `bloodborne` |
| `wuchang` | Wuchang: Fallen Feathers | `wuchang-fallen-feathers` |
| `yakuza0` | Yakuza 0 | `yakuza` |
| `baldursgate3` | Baldur's Gate 3 | `baldursgate` |

Cada chave pode ser ingerida isoladamente. As wikis de Yakuza e Baldur's Gate
cobrem vários jogos, então a ingestão usa apenas as categorias específicas de
Yakuza 0 e Baldur's Gate 3. Nas wikis dedicadas (Sekiro, Bloodborne e Wuchang),
todas as páginas de conteúdo são usadas.

```bash
./gradlew ingestRag --args="darksouls1 bloodborne baldursgate3 --limit=300"
```

### Ingestão em partes (checkpoint)

Para dividir uma ingestão longa em várias noites, use `--resume`. O índice da
última página processada fica na tabela `rag_ingest_state` (uma linha por jogo),
junto com uma assinatura da lista de títulos — se a wiki mudar, o checkpoint é
descartado e a ingestão recomeça.

```bash
# ~30 min por execução, retomando de onde parou
./gradlew ingestRag --args="--all --resume"

# outro orçamento de tempo
./gradlew ingestRag --args="--all --resume --minutes=45"

# um jogo específico
./gradlew ingestRag --args="darksouls3 --resume --minutes=30"
```

Ao estourar o tempo, a execução imprime o progresso e salva o índice:

```text
Checkpoint: darksouls3 1830/3260 (56%) — índice salvo
```

Para acompanhar os checkpoints:

```bash
docker compose exec -T postgres psql -U kate -d kate -c \
  "SELECT game, last_index, total, round(100.0*last_index/total,1) AS pct FROM rag_ingest_state;"
```

`--minutes` sem `--resume` é ignorado, porque sem checkpoint não haveria como
retomar. `--force` apaga o checkpoint do jogo e recomeça do zero.

### Separação Helldivers 1 x Helldivers 2

A wiki `helldivers.fandom.com` cobre os dois jogos. A ingestão usa apenas as
categorias específicas de HD2 (`... (Helldivers 2)`) e descarta páginas com o
prefixo `Helldivers 1:`. Ao ingerir `helldivers2`, os registros antigos de HD1
que estavam gravados com a chave `helldivers2` também são removidos
automaticamente. As buscas vetorial e por palavra-chave ainda aplicam um filtro
defensivo para nunca retornar títulos `Helldivers 1:`.

Para limpar e repopular a base de HD2 após essa correção:

```bash
./gradlew ingestRag --args="helldivers2 --force"
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
scripts/                  Utilitários (detecção de GPU AMD)
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
