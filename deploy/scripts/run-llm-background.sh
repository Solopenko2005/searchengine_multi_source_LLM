#!/usr/bin/env sh
set -eu

# Topic extraction is isolated from interactive chat. The process uses the
# compact 0.6B classifier with fewer CPU threads and a positive nice value so
# the 4B conversational model keeps both CPU and memory bandwidth.
THREADS="${LLM_BACKGROUND_CPU_THREADS:-2}"
PORT="${LLM_BACKGROUND_PORT:-1236}"
CONTEXT="${LLM_BACKGROUND_CONTEXT_SIZE:-4096}"
NICE_LEVEL="${LLM_BACKGROUND_NICE:-10}"

BACKEND="$(find /home/scientific/.lmstudio/extensions/backends -type f -name llama-server \
  -path '*linux-x86_64-avx2-*' | sort | tail -n 1)"
if [ -z "${BACKEND}" ]; then
  BACKEND="$(find /home/scientific/.lmstudio/extensions/backends -type f -name llama-server \
    | sort | tail -n 1)"
fi
MODEL="$(find /home/scientific/.lmstudio/models -type f \
  -iname '*Qwen3-0.6B*Q4_K_M.gguf' | sort | head -n 1)"

if [ -z "${BACKEND}" ] || [ ! -x "${BACKEND}" ]; then
  echo "A compatible llama-server backend was not found" >&2
  exit 1
fi
if [ -z "${MODEL}" ] || [ ! -f "${MODEL}" ]; then
  echo "The Qwen3-0.6B Q4_K_M model was not found" >&2
  exit 1
fi

set -- "${BACKEND}" --model "${MODEL}" --alias "${LLM_BACKGROUND_MODEL:-local-qwen3-0.6b}" \
  --host 0.0.0.0 --port "${PORT}" --no-webui --jinja \
  --ctx-size "${CONTEXT}" --parallel 1 --batch-size 1024 --ubatch-size 256 \
  --threads "${THREADS}" --threads-batch "${THREADS}" \
  --cache-type-k f16 --cache-type-v f16 \
  --reasoning off --reasoning-budget 0

if [ -n "${LLM_API_KEY:-}" ]; then
  set -- "$@" --api-key "${LLM_API_KEY}"
fi

exec nice -n "${NICE_LEVEL}" "$@"
