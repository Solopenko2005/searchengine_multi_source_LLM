#!/usr/bin/env sh
set -eu

# Removes only users created by rag-load-test.mjs --ephemeral true.
DATABASE_CONTAINER="${DATABASE_CONTAINER:-scientific-search-database-1}"

docker exec "${DATABASE_CONTAINER}" sh -lc '
  psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<"SQL"
BEGIN;
DELETE FROM workspace_membership WHERE user_id LIKE '\''rag-evidence-%@example.test'\'';
DELETE FROM assistant_profile WHERE owner_id LIKE '\''rag-evidence-%@example.test'\'';
DELETE FROM assistant_topic_cache WHERE owner_id LIKE '\''rag-evidence-%@example.test'\'';
DELETE FROM auth_schema.password_reset_token
WHERE email LIKE '\''rag-evidence-%@example.test'\'';
DELETE FROM auth_schema.users WHERE email LIKE '\''rag-evidence-%@example.test'\'';
COMMIT;
SQL
'

echo "Ephemeral RAG evidence identities removed"
