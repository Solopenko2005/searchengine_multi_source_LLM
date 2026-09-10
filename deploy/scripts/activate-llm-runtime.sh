#!/usr/bin/env sh
set -eu

APP_DIR="${1:-/home/scientific/app}"
ENV_FILE="${APP_DIR}/deploy/.env.production"

if [ ! -f "${ENV_FILE}" ]; then
  echo "Production environment file is missing: ${ENV_FILE}" >&2
  exit 1
fi

upsert_env() {
  key="$1"
  value="$2"
  if grep -q "^${key}=" "${ENV_FILE}"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "${ENV_FILE}"
  else
    printf '\n%s=%s\n' "${key}" "${value}" >>"${ENV_FILE}"
  fi
}

backup="${ENV_FILE}.before-dedicated-llm"
if [ ! -f "${backup}" ]; then
  cp "${ENV_FILE}" "${backup}"
  chmod 600 "${backup}"
fi

upsert_env LLM_BASE_URL http://host.docker.internal:1235/v1
upsert_env LLM_INFERENCE_PORT 1235
upsert_env LLM_CPU_THREADS 8
upsert_env LLM_DRAFT_CPU_THREADS 2
upsert_env LLM_CONTEXT_SIZE 4096
upsert_env LLM_SPECULATIVE_DECODING false
upsert_env LLM_BACKGROUND_BASE_URL http://host.docker.internal:1236/v1
upsert_env LLM_BACKGROUND_PORT 1236
upsert_env LLM_BACKGROUND_CPU_THREADS 2
upsert_env LLM_BACKGROUND_CONTEXT_SIZE 4096
upsert_env LLM_BACKGROUND_NICE 10
upsert_env EMBEDDING_BASE_URL http://host.docker.internal:1234/v1
upsert_env ASSISTANT_RAG_LOCAL_CONTEXT_CHARS 720
upsert_env OPENAI_MAX_OUTPUT_TOKENS 128
upsert_env OPENAI_BACKGROUND_TIMEOUT_SECONDS 45
upsert_env OPENAI_BACKGROUND_MAX_OUTPUT_TOKENS 192

# The production LM Studio server and the dedicated llama-server use the same
# bearer token. Preserve the existing secret without printing it.
llm_key="$(sed -n 's/^LLM_API_KEY=//p' "${ENV_FILE}" | tail -n 1 | tr -d '\r')"
llm_key="${llm_key#\"}"
llm_key="${llm_key%\"}"
if [ -z "${llm_key}" ]; then
  echo "LLM_API_KEY is missing" >&2
  exit 1
fi
upsert_env EMBEDDING_API_KEY "${llm_key}"
upsert_env LLM_BACKGROUND_API_KEY "${llm_key}"
upsert_env LLM_BACKGROUND_MODEL local-qwen3-0.6b
chmod 600 "${ENV_FILE}"

chmod 0755 "${APP_DIR}/deploy/scripts/run-llm-inference.sh"
chmod 0755 "${APP_DIR}/deploy/scripts/run-llm-background.sh"
sudo install -m 0644 "${APP_DIR}/deploy/systemd/lmstudio.service" \
  /etc/systemd/system/lmstudio.service
sudo install -m 0644 "${APP_DIR}/deploy/systemd/llm-inference.service" \
  /etc/systemd/system/llm-inference.service
sudo install -m 0644 "${APP_DIR}/deploy/systemd/llm-background.service" \
  /etc/systemd/system/llm-background.service
sudo ufw allow from 172.28.0.0/24 to any port 1235 proto tcp \
  comment 'Scientific Search Qwen bridge'
sudo ufw allow from 172.28.0.0/24 to any port 1236 proto tcp \
  comment 'Scientific Search topic LLM bridge'
sudo systemctl daemon-reload
sudo systemctl restart lmstudio.service
sudo systemctl enable --now llm-inference.service
sudo systemctl enable llm-background.service
sudo systemctl restart llm-background.service

cd "${APP_DIR}"
sudo docker compose --env-file deploy/.env.production -f deploy/docker-compose.yml \
  up -d --no-deps --force-recreate search

echo "Dedicated LLM runtime activated"
