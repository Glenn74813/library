#!/usr/bin/env bash
# Non-destructive restore test (for the acceptance record and the term-by-term check in the admin manual).
#   scripts/restore-test.sh [backups/db-YYYYMMDD-HHMMSS.dump] [backups/files-YYYYMMDD-HHMMSS.tar.gz]
# Restores a backup (the newest one by default) into a scratch database, compares every table's row
# count with the live database, checks the uploaded-files archive opens, then deletes the scratch copy.
# The live library database and files are not touched. Take a fresh backup first for an exact match:
#   docker compose exec backup /bin/sh /backup.sh once
set -euo pipefail
cd "$(dirname "$0")/.."
DUMP="${1:-$(ls -t backups/db-*.dump | head -1)}"
FILES="${2:-$(ls -t backups/files-*.tar.gz 2>/dev/null | head -1 || true)}"
[ -f "$DUMP" ] || { echo "No such file: $DUMP"; exit 1; }
DB=restore_test
sql() { docker compose exec -T db psql -U smartlibrary -At "$@"; }

echo "Restore test started $(date '+%Y-%m-%d %H:%M:%S')"
echo "Database backup: $DUMP ($(du -h "$DUMP" | cut -f1))"
sql -d smartlibrary -qc "drop database if exists $DB" -c "create database $DB" >/dev/null
docker compose exec -T db pg_restore --no-owner -U smartlibrary -d "$DB" < "$DUMP"

printf '\n%-28s %10s %10s  %s\n' "Table" "Backup" "Live" "Result"
fail=0
for t in $(sql -d smartlibrary -c "select tablename from pg_tables where schemaname = 'public' order by 1"); do
  restored=$(sql -d "$DB" -c "select count(*) from \"$t\"" 2>/dev/null || echo "missing")
  live=$(sql -d smartlibrary -c "select count(*) from \"$t\"")
  if [ "$restored" = "$live" ]; then r="OK"; else r="DIFFERENT"; fail=1; fi
  printf '%-28s %10s %10s  %s\n' "$t" "$restored" "$live" "$r"
done
migrations=$(sql -d "$DB" -c "select max(version::int) from flyway_schema_history where success")
echo
echo "Schema version in backup: V$migrations"

if [ -n "$FILES" ] && [ -f "$FILES" ]; then
  in_archive=$(tar -tzf "$FILES" | grep -vc '/$' || true)
  live_files=$(docker compose run --rm --no-deps --entrypoint sh backup -c "find /data/files -type f | wc -l" 2>/dev/null | tail -1)
  echo "Uploaded files: $in_archive in $FILES, $live_files on the server"
  [ "$in_archive" = "$live_files" ] || fail=1
fi

sql -d smartlibrary -qc "drop database $DB" >/dev/null
echo
if [ "$fail" = 0 ]; then
  echo "RESULT: PASSED - the backup restores completely and matches the live data."
else
  echo "RESULT: DIFFERENCES - counts differ (normal if the library was used after the backup was taken;"
  echo "take a fresh backup and run again for an exact match)."
fi
exit "$fail"
