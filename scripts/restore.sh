#!/usr/bin/env bash
# Restores a backup taken by the "backup" service.
#   scripts/restore.sh backups/db-YYYYMMDD-HHMMSS.dump [backups/files-YYYYMMDD-HHMMSS.tar.gz]
# Stops the API, replaces the database (and uploaded files if given), then starts the API again.
set -euo pipefail
cd "$(dirname "$0")/.."
DUMP="${1:?usage: scripts/restore.sh <db dump> [files tar.gz]}"
FILES="${2:-}"
[ -f "$DUMP" ] || { echo "No such file: $DUMP"; exit 1; }
read -r -p "This REPLACES the current library database${FILES:+ and uploaded files}. Type RESTORE to continue: " ok
[ "$ok" = "RESTORE" ] || { echo "Cancelled."; exit 1; }

docker compose stop api
docker compose exec -T db pg_restore --clean --if-exists --no-owner -U smartlibrary -d smartlibrary < "$DUMP"
if [ -n "$FILES" ]; then
  docker compose run --rm --no-deps -v "$(pwd)/$(dirname "$FILES")":/restore:ro --entrypoint sh backup \
    -c "rm -rf /data/files/* && tar -xzf /restore/$(basename "$FILES") -C /data"
fi
docker compose start api
echo "Restore finished."
