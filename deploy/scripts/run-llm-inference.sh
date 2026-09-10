#!/usr/bin/env sh
set -eu

# Run the production Qwen model in the LM Studio llama.cpp backend with an
# explicit CPU budget. LM Studio's default auto-selection used only three of
# the eight VPS CPUs and made time-to-first-token several times slower.
THREADS="${LLM_CPU_THREADS:-8}"
PORT="${LLM_INFERENCE_PORT:-1235}"
CONTEXT="${LLM_CONTEXT_SIZE:-4096}"
DRAFT_THREADS="${LLM_DRAFT_CPU_THREADS:-2}"

BACKEND="$(find /home/scientific/.lmstudio/extensions/backends -type f -name llama-server \
  -path '*linux-x86_64-avx2-*' | sort | tail -n 1)"
if [ -z "${BACKEND}" ]; then
  BACKEND="$(find /home/scientific/.lmstudio/extensions/backends -type f -name llama-server \
    | sort | tail -n 1)"
fi
MODEL="$(find /home/scientific/.lmstudio/models -type f \
  -iname '*Qwen3-4B*Q4_K_M.gguf' | sort | head -n 1)"
DRAFT_MODEL="$(find /home/scientific/.lmstudio/models -type f \
  -iname '*Qwen3-0.6B*Q4_K_M.gguf' | sort | head -n 1)"

if [ -z "${BACKEND}" ] || [ ! -x "${BACKEND}" ]; then
  echo "A compatible llama-server backend was not found" >&2
  exit 1
fi
if [ -z "${MODEL}" ] || [ ! -f "${MODEL}" ]; then
  echo "The Qwen3-4B Q4_K_M model was not found" >&2
  exit 1
fi

set -- "${BACKEND}" --model "${MODEL}" --host 0.0.0.0 --port "${PORT}" \
  --no-webui --jinja --ctx-size "${CONTEXT}" --parallel 1 \
  --batch-size 2048 --ubatch-size 512 --threads "${THREADS}" \
  --threads-batch "${THREADS}" --cache-type-k f16 --cache-type-v f16 \
  --reasoning off --reasoning-budget 0 --reasoning-format none

if [ -n "${LLM_API_KEY:-}" ]; then
  set -- "$@" --api-key "${LLM_API_KEY}"
fi
if [ "${LLM_SPECULATIVE_DECODING:-true}" = "true" ] && [ -f "${DRAFT_MODEL}" ]; then
  set -- "$@" --spec-draft-model "${DRAFT_MODEL}" --spec-draft-n-max 16 \
    --spec-draft-n-min 1 --spec-draft-threads "${DRAFT_THREADS}" \
    --spec-draft-threads-batch "${DRAFT_THREADS}"
fi

exec "$@"
