#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${1:-/home/scientific/app}"
DEPLOY_DIR="${APP_DIR}/deploy"
PUBLIC_URL="https://search.5-42-117-227.sslip.io"

cd "${DEPLOY_DIR}"

echo "== Containers =="
docker compose --env-file .env.production ps

echo "== Public HTTPS =="
curl --fail --silent --show-error --location --max-time 20 \
  --output /dev/null --write-out 'HTTP %{http_code}\n' "${PUBLIC_URL}/"

echo "== LLM chat =="
curl --fail --silent --show-error --max-time 180 \
  --header 'Content-Type: application/json' \
  --data-binary @- http://127.0.0.1:1234/v1/chat/completions <<'JSON' \
  | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data["choices"][0]["message"]["content"].strip())'
{"model":"local-qwen3-8b","messages":[{"role":"user","content":"/no_think Ответьте только одним словом: работает"}],"temperature":0,"max_tokens":16}
JSON

echo "== Embeddings =="
curl --fail --silent --show-error --max-time 60 \
  --header 'Content-Type: application/json' \
  --data-binary @- http://127.0.0.1:1234/v1/embeddings <<'JSON' \
  | python3 -c 'import json,sys; data=json.load(sys.stdin); print("dimensions", len(data["data"][0]["embedding"]))'
{"model":"text-embedding-nomic-embed-text-v1.5","input":"проверка научного поиска"}
JSON

echo "== Resources =="
free -h
df -h /
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
