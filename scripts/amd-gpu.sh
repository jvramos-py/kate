#!/usr/bin/env bash
#
# Detecta a GPU AMD e recomenda o HSA_OVERRIDE_GFX_VERSION para usar o Ollama
# com ROCm.
#
# O caminho recomendado para AMD no Docker é Vulkan
# (docker-compose.gpu-amd.yml), que NÃO precisa desta variável.
#
# Uso:
#   ./scripts/amd-gpu.sh           # mostra a GPU detectada e a recomendação
#   ./scripts/amd-gpu.sh --write   # grava/atualiza HSA_OVERRIDE_GFX_VERSION no .env
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname -- "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

usage() {
  cat <<'EOF'
Uso: ./scripts/amd-gpu.sh [--write]

  (sem argumentos)  Detecta a GPU AMD e mostra a configuração recomendada.
  --write           Grava HSA_OVERRIDE_GFX_VERSION no .env (ou remove, quando a
                    GPU é suportada nativamente pelo ROCm).
EOF
}

WRITE=0
case "${1:-}" in
  "") ;;
  --write) WRITE=1 ;;
  -h | --help)
    usage
    exit 0
    ;;
  *)
    echo "Opção desconhecida: $1" >&2
    usage >&2
    exit 2
    ;;
esac

CHIP=""
GPU_NAME=""
GFX=""

is_igpu_chip() {
  case "$1" in
    RENOIR | CEZANNE | BARCELO | PICASSO | RAVEN | RAVEN2 | VANGOGH | REMBRANDT | \
      MENDOCINO | LUCY | WREN | ARDEN | YELLOW_CARP | GRANITE_RIDGE | \
      PHOENIX* | HAWKPOINT* | STRIX* | GFX90C | GFX115*) return 0 ;;
    *) return 1 ;;
  esac
}

chip_to_gfx() {
  case "$1" in
    NAVI10) echo gfx1010 ;;
    NAVI12) echo gfx1011 ;;
    NAVI14) echo gfx1012 ;;
    NAVI21) echo gfx1030 ;;
    NAVI22) echo gfx1031 ;;
    NAVI23) echo gfx1032 ;;
    NAVI24) echo gfx1034 ;;
    NAVI31) echo gfx1100 ;;
    NAVI32) echo gfx1101 ;;
    NAVI33) echo gfx1102 ;;
    NAVI44) echo gfx1200 ;;
    NAVI48) echo gfx1201 ;;
    PHOENIX | PHOENIX1 | PHOENIX2 | HAWKPOINT | GFX1103) echo gfx1103 ;;
    STRIX1 | STRIX_HALO | GFX1150) echo gfx1150 ;;
    STRIX2 | GFX1151) echo gfx1151 ;;
    VANGOGH) echo gfx1033 ;;
    RENOIR | CEZANNE | BARCELO) echo gfx90c ;;
    VEGA10) echo gfx900 ;;
    VEGA20) echo gfx906 ;;
    POLARIS10 | POLARIS11 | POLARIS12) echo gfx803 ;;
    *) echo "" ;;
  esac
}

pci_id_to_chip() {
  case "$1" in
    731f) echo NAVI10 ;;
    7340) echo NAVI14 ;;
    73bf | 73a5) echo NAVI21 ;;
    73df) echo NAVI22 ;;
    73ef) echo NAVI23 ;;
    73ff | 743f) echo NAVI24 ;;
    744c) echo NAVI31 ;;
    747e | 7480) echo NAVI32 ;;
    7470) echo NAVI33 ;;
    7550) echo NAVI48 ;;
    7590) echo NAVI44 ;;
    *) echo "" ;;
  esac
}

gfx_family() {
  case "$1" in
    gfx101*) echo "RDNA1" ;;
    gfx103*) echo "RDNA2" ;;
    gfx110*) echo "RDNA3" ;;
    gfx115*) echo "RDNA3.5" ;;
    gfx120*) echo "RDNA4" ;;
    gfx9*) echo "GCN/CDNA" ;;
    *) echo "desconhecida" ;;
  esac
}

gfx_to_override() {
  case "$1" in
    gfx1010 | gfx1011 | gfx1012) echo "10.3.0" ;;
    gfx1031 | gfx1032 | gfx1033 | gfx1034) echo "10.3.0" ;;
    gfx1102 | gfx1103) echo "11.0.0" ;;
    gfx1150 | gfx1151) echo "11.5.0" ;;
    *) echo "" ;;
  esac
}

is_native() {
  case "$1" in
    gfx1030 | gfx1100 | gfx1101 | gfx1200 | gfx1201 | gfx90a | gfx942 | gfx908) return 0 ;;
    *) return 1 ;;
  esac
}

is_unsupported() {
  # gfx803 (Polaris) e gfx900/gfx906 (Vega) foram removidos do ROCm 6+.
  case "$1" in
    gfx803 | gfx900 | gfx906) return 0 ;;
    *) return 1 ;;
  esac
}

detect_vulkan() {
  command -v vulkaninfo >/dev/null 2>&1 || return 1

  local names name chip
  names=$(vulkaninfo --summary 2>/dev/null | sed -n 's/.*deviceName[[:space:]]*=[[:space:]]*//p') || true
  [ -n "$names" ] || return 1

  # Prefere a GPU discreta (ignora iGPU/llvmpipe).
  while IFS= read -r name; do
    case "$name" in
      *"(RADV "*) ;;
      *) continue ;;
    esac
    chip=$(printf '%s' "$name" | sed -n 's/.*(RADV \([A-Z0-9_]*\)).*/\1/p')
    [ -n "$chip" ] || continue
    if ! is_igpu_chip "$chip"; then
      GPU_NAME="$name"
      CHIP="$chip"
      return 0
    fi
  done <<<"$names"

  # Só há iGPU.
  name=$(printf '%s\n' "$names" | head -1)
  chip=$(printf '%s' "$name" | sed -n 's/.*(RADV \([A-Z0-9_]*\)).*/\1/p')
  [ -n "$chip" ] || return 1
  GPU_NAME="$name"
  CHIP="$chip"
}

detect_rocminfo() {
  command -v rocminfo >/dev/null 2>&1 || return 1

  local gfx
  gfx=$(rocminfo 2>/dev/null | grep -oE 'gfx[0-9a-f]{3,4}' | grep -v '^gfx90c$' | head -1) || true
  [ -n "$gfx" ] || return 1
  GFX="$gfx"
}

detect_lspci() {
  command -v lspci >/dev/null 2>&1 || return 1

  local line id chip
  line=$(lspci -nn 2>/dev/null | grep -iE 'vga|display|3d' | grep -iE 'amd|ati' |
    grep -viE 'renoir|cezanne|barcelo|rembrandt|phoenix|hawk point|strix|lucienne|mendocino|vangogh|picasso|raven' |
    head -1) || true
  [ -n "$line" ] || return 1

  id=$(printf '%s' "$line" | grep -oE '\[1002:[0-9a-f]{4}\]' | head -1 | tr -d '[]' | cut -d: -f2) || true
  chip=$(pci_id_to_chip "$id")

  if [ -z "$chip" ] && [[ "$line" =~ Navi[[:space:]]([0-9]+) ]]; then
    chip="NAVI${BASH_REMATCH[1]}"
  fi

  [ -n "$chip" ] || return 1
  CHIP="$chip"
}

detect_vulkan || true
if [ -z "$GFX" ] && [ -z "$CHIP" ]; then
  detect_rocminfo || true
fi
if [ -z "$GFX" ] && [ -z "$CHIP" ]; then
  detect_lspci || true
fi

if [ -n "$CHIP" ] && [ -z "$GFX" ]; then
  GFX=$(chip_to_gfx "$CHIP")
fi

if [ -z "$GFX" ]; then
  echo "Não foi possível identificar uma GPU AMD." >&2
  echo "O caminho Vulkan não precisa de configuração:" >&2
  echo "  docker compose -f docker-compose.yml -f docker-compose.gpu-amd.yml up -d" >&2
  exit 1
fi

SUFFIX=""
if [ -n "$GPU_NAME" ] && [ -n "$CHIP" ]; then
  case "$GPU_NAME" in
    *"$CHIP"*) ;;
    *) SUFFIX=" ($CHIP)" ;;
  esac
fi

echo "GPU: ${GPU_NAME:-desconhecida}${SUFFIX}"
echo "gfx: $GFX ($(gfx_family "$GFX"))"
echo

RECOMMEND=""
if is_native "$GFX"; then
  echo "ROCm: suportada nativamente — não precisa de HSA_OVERRIDE_GFX_VERSION."
elif is_unsupported "$GFX"; then
  echo "ROCm: sem suporte no ROCm atual — use Vulkan."
else
  RECOMMEND=$(gfx_to_override "$GFX")
  if [ -n "$RECOMMEND" ]; then
    echo "ROCm: HSA_OVERRIDE_GFX_VERSION=$RECOMMEND"
  else
    echo "ROCm: arquitetura sem receita conhecida — prefira Vulkan."
  fi
fi

echo
echo "Vulkan (recomendado, sem configuração):"
echo "  docker compose -f docker-compose.yml -f docker-compose.gpu-amd.yml up -d"

if [ -n "$RECOMMEND" ]; then
  echo
  echo "ROCm (opcional):"
  echo "  ./scripts/amd-gpu.sh --write"
  echo "  docker compose -f docker-compose.yml -f docker-compose.gpu-amd-rocm.yml up -d"
fi

if [ "$WRITE" -eq 1 ]; then
  if [ ! -f "$ENV_FILE" ]; then
    if [ -f "$PROJECT_DIR/.env.example" ]; then
      cp "$PROJECT_DIR/.env.example" "$ENV_FILE"
      echo
      echo "Criado .env a partir de .env.example — preencha BOT_TOKEN."
    else
      : >"$ENV_FILE"
    fi
  fi

  if [ -n "$RECOMMEND" ]; then
    if grep -qE '^#?[[:space:]]*HSA_OVERRIDE_GFX_VERSION=' "$ENV_FILE"; then
      sed -i -E "s|^#?[[:space:]]*HSA_OVERRIDE_GFX_VERSION=.*|HSA_OVERRIDE_GFX_VERSION=$RECOMMEND|" "$ENV_FILE"
    else
      printf '\nHSA_OVERRIDE_GFX_VERSION=%s\n' "$RECOMMEND" >>"$ENV_FILE"
    fi
    echo "✔ .env atualizado: HSA_OVERRIDE_GFX_VERSION=$RECOMMEND"
  else
    if grep -qE '^HSA_OVERRIDE_GFX_VERSION=' "$ENV_FILE"; then
      sed -i -E 's|^HSA_OVERRIDE_GFX_VERSION=.*|# HSA_OVERRIDE_GFX_VERSION=|' "$ENV_FILE"
    fi
    echo "✔ Nada a configurar no .env."
  fi
fi
