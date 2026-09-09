#!/usr/bin/env sh
set -eu

cd /home/scientific/app
output="${1:-}"
if [ -n "$output" ]; then
  mkdir -p "$(dirname "$output")"
  exec >"$output" 2>&1
fi

echo "[containers]"
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.production ps --format json

echo "[database-counts]"
docker exec scientific-search-database-1 sh -lc \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  "SELECT json_build_object('\''sites'\'', (SELECT count(*) FROM site), '\''pages'\'', (SELECT count(*) FROM page), '\''lemmas'\'', (SELECT count(*) FROM lemma), '\''search_index'\'', (SELECT count(*) FROM search_index), '\''assistant_chunks'\'', (SELECT count(*) FROM assistant_chunk));"'

echo "[largest-tables]"
docker exec scientific-search-database-1 sh -lc \
  'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  "SELECT relname || '\''='\'' || pg_size_pretty(pg_total_relation_size(relid)) FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 8;"'

echo "[redis]"
docker exec scientific-search-redis-1 sh -lc \
  'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping'

echo "[authorization-openapi]"
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.production exec -T authorization \
  wget -qO- http://127.0.0.1:5555/v3/api-docs | grep -o '"/api/v1/auth[^" ]*"' | sort -u

echo "[email-health]"
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.production exec -T email \
  wget -qO- http://127.0.0.1:8771/api/v1/email/health

echo
echo "[llm-generation-health]"
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.production exec -T search \
  sh -lc 'wget -qO- --timeout=15 --header="Authorization: Bearer $OPENAI_API_KEY" http://host.docker.internal:1235/health'

echo
echo "[embedding-models]"
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.production exec -T search \
  wget -qO- --timeout=15 http://host.docker.internal:1234/v1/models

echo
echo "[resource-snapshot]"
docker stats --no-stream --format '{{json .}}'
