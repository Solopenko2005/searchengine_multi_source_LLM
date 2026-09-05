#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="/home/scientific/app/deploy"
DUMP_FILE="/home/scientific/search-engine-current.dump"
VECTOR_ARCHIVE="/home/scientific/assistant-vectors.tar.gz"
DATABASE_CONTAINER="scientific-search-database-1"

cd "${APP_DIR}"

database_state=""
for _ in $(seq 1 30); do
  database_state=$(docker inspect --format='{{.State.Health.Status}}' "${DATABASE_CONTAINER}" 2>/dev/null || true)
  if [[ "${database_state}" == "healthy" ]]; then
    break
  fi
  sleep 2
done

if [[ "${database_state}" != "healthy" ]]; then
  echo "PostgreSQL did not become healthy." >&2
  exit 1
fi

docker cp "${DUMP_FILE}" "${DATABASE_CONTAINER}:/tmp/search-engine-current.dump"
docker compose --env-file .env.production exec -T database \
  pg_restore --username=search_app --dbname=search_engine \
  --no-owner --no-acl --exit-on-error /tmp/search-engine-current.dump
docker compose --env-file .env.production exec -T database \
  rm -f /tmp/search-engine-current.dump

docker volume create scientific-search_assistant_vectors >/dev/null
vector_mount=$(docker volume inspect --format='{{.Mountpoint}}' scientific-search_assistant_vectors)
sudo tar -xzf "${VECTOR_ARCHIVE}" --strip-components=1 -C "${vector_mount}"
app_uid=$(docker run --rm --entrypoint id scientific-search-search -u app)
app_gid=$(docker run --rm --entrypoint id scientific-search-search -g app)
sudo chown -R "${app_uid}:${app_gid}" "${vector_mount}"

docker compose --env-file .env.production exec -T database \
  psql --username=search_app --dbname=search_engine --tuples-only \
  --command='SELECT count(*) AS sources FROM site; SELECT count(*) AS pages FROM page;'

echo "Database and assistant vector index restored."
