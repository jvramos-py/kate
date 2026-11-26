# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:21-jdk-noble AS build

RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential ca-certificates cmake git \
 && rm -rf /var/lib/apt/lists/*

# whisper.cpp -> binário estático em /usr/local/bin/whisper-cli
ARG WHISPER_CPP_REF=v1.9.6
RUN git clone --depth 1 --branch "${WHISPER_CPP_REF}" https://github.com/ggml-org/whisper.cpp /tmp/whisper.cpp \
 && cmake -S /tmp/whisper.cpp -B /tmp/whisper.cpp/build \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DGGML_NATIVE=OFF \
      -DWHISPER_BUILD_TESTS=OFF \
      -DWHISPER_BUILD_SERVER=OFF \
 && cmake --build /tmp/whisper.cpp/build --target whisper-cli -j "$(nproc)" \
 && install -m 0755 /tmp/whisper.cpp/build/bin/whisper-cli /usr/local/bin/whisper-cli

WORKDIR /src

# cache das dependências do Gradle
COPY gradle ./gradle
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon --version

COPY src ./src
RUN ./gradlew --no-daemon installDist

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl ffmpeg libgomp1 python3 python3-venv tini \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -m venv /opt/piper \
 && /opt/piper/bin/pip install --no-cache-dir "piper-tts==1.8.0" \
 && ln -sf /opt/piper/bin/piper /usr/local/bin/piper-tts

COPY --from=build /usr/local/bin/whisper-cli /usr/local/bin/whisper-cli
COPY --from=build /src/build/install/kate /app
COPY lavalink/sounds /app/sounds

ENV WHISPER_CLI_PATH=/usr/local/bin/whisper-cli \
    WHISPER_MODEL_PATH=/app/models/ggml-base.bin \
    PIPER_VOICE_PATH=/app/voices/pt_BR-dii-high.onnx \
    KATE_SOUNDS_DIR=/app/sounds \
    KATE_INTRO_SOUND=/app/sounds/voce-nao-tem-aura.mp3 \
    HOME=/home/kate

# Reaproveita o UID 1000 da imagem base (usuário "ubuntu") como "kate",
# para que bind mounts do host com UID 1000 funcionem sem ajustes.
RUN groupmod -n kate ubuntu \
 && usermod -l kate -d /home/kate -m ubuntu \
 && chown -R kate:kate /app/sounds

USER kate
WORKDIR /app
ENTRYPOINT ["/usr/bin/tini", "--", "/app/bin/kate"]
