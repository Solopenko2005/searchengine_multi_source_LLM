#!/usr/bin/env sh
set -eu

# Temporary benchmark of the same GGUF with an explicit CPU thread count.
# It binds only to loopback and always terminates the temporary process.
ROOT="${1:-/home/scientific/app}"
THREADS="${LLM_BENCHMARK_THREADS:-6}"
DRAFT_THREADS="${LLM_BENCHMARK_DRAFT_THREADS:-2}"
PORT="${LLM_BENCHMARK_PORT:-1235}"
OUTPUT="${LLM_BENCHMARK_OUTPUT:-/home/scientific/llm-thread-benchmark.json}"
BACKEND="$(find /home/scientific/.lmstudio/extensions/backends -type f -name llama-server \
  -path '*linux-x86_64-avx2-*' | sort | tail -n 1)"
MODEL="$(find /home/scientific/.lmstudio/models -type f -path '*Qwen3-4B*Q4_K_M.gguf' | head -n 1)"
DRAFT_MODEL="$(find /home/scientific/.lmstudio/models -type f -path '*Qwen3-0.6B*Q4_K_M.gguf' | head -n 1)"
LOG="/home/scientific/llm-thread-benchmark.log"

test -x "${BACKEND}"
test -f "${MODEL}"

set -- "${BACKEND}" --model "${MODEL}" --host 127.0.0.1 --port "${PORT}" \
  --api-key lm-studio --no-webui --jinja --ctx-size 4096 --parallel 1 \
  --batch-size 2048 --ubatch-size 512 --threads "${THREADS}" \
  --threads-batch "${THREADS}" --cache-type-k f16 --cache-type-v f16
if [ "${LLM_BENCHMARK_DRAFT:-false}" = "true" ] && [ -f "${DRAFT_MODEL}" ]; then
  set -- "$@" --spec-draft-model "${DRAFT_MODEL}" --spec-draft-n-max 16 \
    --spec-draft-n-min 1 --spec-draft-threads "${DRAFT_THREADS}" \
    --spec-draft-threads-batch "${DRAFT_THREADS}"
fi
"$@" >"${LOG}" 2>&1 &
PID="$!"
cleanup() {
  kill "${PID}" 2>/dev/null || true
  wait "${PID}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

attempt=0
until curl --fail --silent --max-time 2 "http://127.0.0.1:${PORT}/health" >/dev/null; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 60 ]; then
    tail -n 40 "${LOG}"
    exit 1
  fi
  sleep 1
done

python3 "${ROOT}/scripts/testing/llm-smoke-test.py" \
  --base "http://127.0.0.1:${PORT}/v1" --model local-qwen3-4b --output "${OUTPUT}"
