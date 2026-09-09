#!/usr/bin/env sh
set -eu

# Run on the application host. The invitation is written to a protected
# temporary file and is never printed to CI or terminal output.
OUTPUT="${1:-/home/scientific/rag-invite.tmp}"
DATABASE_CONTAINER="${DATABASE_CONTAINER:-scientific-search-database-1}"

docker exec "${DATABASE_CONTAINER}" sh -lc '
  psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "
    SELECT g.invite_code
    FROM workspace_group g
    LEFT JOIN site s ON LOWER(s.owner_id) = LOWER(g.owner_id)
    GROUP BY g.id, g.invite_code
    ORDER BY COUNT(s.id) DESC, g.id
    LIMIT 1
  "
' >"${OUTPUT}"

chmod 600 "${OUTPUT}"
test -s "${OUTPUT}"
echo "Invitation fixture prepared (${OUTPUT}, $(wc -c <"${OUTPUT}") bytes)"
