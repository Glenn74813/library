#!/bin/sh
# Database + uploaded-files backup. Runs inside the "backup" compose service.
#   backup.sh once   -> take one backup now
#   backup.sh loop   -> take one backup per day at BACKUP_HOUR (local time), keep BACKUP_KEEP_DAYS days
set -eu
DIR=/backups
KEEP="${BACKUP_KEEP_DAYS:-14}"
HOUR="${BACKUP_HOUR:-2}"

backup() {
  ts=$(date +%Y%m%d-%H%M%S)
  pg_dump --format=custom --file="$DIR/db-$ts.dump.part"
  mv "$DIR/db-$ts.dump.part" "$DIR/db-$ts.dump"
  tar -czf "$DIR/files-$ts.tar.gz.part" -C /data files
  mv "$DIR/files-$ts.tar.gz.part" "$DIR/files-$ts.tar.gz"
  # sanity check: the dump must be readable
  pg_restore --list "$DIR/db-$ts.dump" > /dev/null
  find "$DIR" -maxdepth 1 \( -name 'db-*.dump' -o -name 'files-*.tar.gz' \) -mtime +"$KEEP" -delete
  date +%F > "$DIR/.last-backup-date"
  echo "$(date '+%F %T') backup OK: db-$ts.dump ($(du -h "$DIR/db-$ts.dump" | cut -f1)), files-$ts.tar.gz"
}

case "${1:-loop}" in
  once) backup ;;
  loop)
    echo "Daily backups at ${HOUR}:00 $(date +%Z), keeping ${KEEP} days, in $DIR"
    while true; do
      if [ "$(date +%-H)" -ge "$HOUR" ] && [ "$(cat "$DIR/.last-backup-date" 2>/dev/null)" != "$(date +%F)" ]; then
        backup || echo "$(date '+%F %T') BACKUP FAILED"
      fi
      sleep 600
    done ;;
  *) echo "usage: backup.sh once|loop"; exit 2 ;;
esac
