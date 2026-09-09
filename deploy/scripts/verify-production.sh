#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${1:-/home/scientific/app}"
DEPLOY_DIR="${APP_DIR}/deploy"
PUBLIC_URL="https://search.5-42-117-227.sslip.io"

cd "${DEPLOY_DIR}"
LLM_API_KEY="$(sed -n 's/^LLM_API_KEY=//p' .env.production | tail -n 1 | tr -d '\r')"
LLM_API_KEY="${LLM_API_KEY#\"}"
LLM_API_KEY="${LLM_API_KEY%\"}"

echo "== Containers =="
docker compose --env-file .env.production ps

echo "== Public HTTPS =="
curl --fail --silent --show-error --location --max-time 20 \
  --output /dev/null --write-out 'HTTP %{http_code}\n' "${PUBLIC_URL}/"

echo "== LLM responses =="
curl --fail --silent --show-error --max-time 180 \
  --header 'Content-Type: application/json' \
  --header "Authorization: Bearer ${LLM_API_KEY}" \
  --data-binary @- http://127.0.0.1:1235/v1/responses <<'JSON' \
  | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data["output"][0]["content"][0]["text"].strip())'
{"model":"local-qwen3-4b","store":false,"max_output_tokens":32,"input":[{"role":"user","content":"/no_think\nОтветьте только одним словом: работает"}],"text":{"format":{"type":"text"}}}
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
