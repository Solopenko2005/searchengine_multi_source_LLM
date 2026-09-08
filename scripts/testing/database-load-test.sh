#!/usr/bin/env sh
set -eu

result_dir="${1:-/home/scientific/database-load-evidence}"
mkdir -p "$result_dir"

database="scientific-search-database-1"
top_lemma=$(docker exec "$database" sh -lc \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT lemma FROM lemma WHERE length(lemma) >= 5 AND frequency > 0 ORDER BY frequency DESC, lemma LIMIT 1"')
if [ -z "$top_lemma" ]; then
  echo "No lemma is available for the read-only database load test" >&2
  exit 1
fi

docker exec -i "$database" sh -lc 'cat > /tmp/scientific-search-read.sql' <<'SQL'
SELECT si.page_id, SUM(si.ranking) AS relevance
FROM search_index si
JOIN lemma l ON l.id = si.lemma_id
WHERE l.lemma = (
  SELECT lemma FROM lemma
  WHERE length(lemma) >= 5 AND frequency > 0
  ORDER BY frequency DESC, lemma
  LIMIT 1
)
GROUP BY si.page_id
ORDER BY relevance DESC
LIMIT 10;
SQL

run_stage() {
  clients="$1"
  duration="$2"
  docker exec "$database" sh -lc \
    "pgbench -n -r -M prepared -c $clients -j $clients -T $duration -f /tmp/scientific-search-read.sql -U \"\$POSTGRES_USER\" \"\$POSTGRES_DB\"" \
    > "$result_dir/database-c${clients}.txt"
}

run_stage 1 10
run_stage 5 10
run_stage 10 10

printf '%s\n' "$top_lemma" > "$result_dir/tested-lemma.txt"
docker exec "$database" rm -f /tmp/scientific-search-read.sql
echo "Read-only database load test completed: $result_dir"

