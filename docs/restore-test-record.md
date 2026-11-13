# Backup restore test record

Requirement: Bid Bulletin No. 131-2026, Term 4, Security & Backup: "automated daily backups with a
tested restore procedure".

| | |
|---|---|
| Date | November 13, 2026, 1:13 PM |
| Backup tested | `backups/db-20261113-131315.dump` (database) and `backups/files-20261113-131315.tar.gz` (uploaded files), taken by the automatic backup service |
| Method | `scripts/restore-test.sh`: the backup is restored into a separate scratch database, every table's row count is compared with the live database, the files archive is checked, then the scratch copy is deleted. The live system is not touched. |
| Result | **PASSED.** All 31 tables restored with the same row counts as the live database; schema version V25; 2 of 2 uploaded files present. |
| Tested by | |
| Witnessed by (LRC) | |

## Steps

1. Take a fresh backup: `docker compose exec backup /bin/sh /backup.sh once`
2. Run the test: `scripts/restore-test.sh`
3. Check the last line says **RESULT: PASSED**, then sign this record.

A real restore (after data loss) uses `scripts/restore.sh <db dump> <files archive>`, which replaces the
live database and files; see the administrator manual, section 9.

## Output

```
Restore test started 2026-11-13 13:13:19
Database backup: backups/db-20261113-131315.dump (148K)

Table                            Backup       Live  Result
app_setting                          74         74  OK
app_user                              5          5  OK
attendance_log                       39         39  OK
audit_log                           708        708  OK
authority                             2          2  OK
bib_record                           49         49  OK
clearance                             1          1  OK
closed_day                            0          0  OK
course                                6          6  OK
custom_field                          0          0  OK
digital_file                          2          2  OK
ebook_view                           11         11  OK
fine                                 12         12  OK
flyway_schema_history                25         25  OK
gate_event                           45         45  OK
hold_request                         24         24  OK
item                                 65         65  OK
library_aisle                         9          9  OK
library_hours                         7          7  OK
library_section                       9          9  OK
loan                                 21         21  OK
notice                               22         22  OK
offline_applied                       0          0  OK
patron                               24         24  OK
patron_category                       5          5  OK
pin_reset                             0          0  OK
print_log                            63         63  OK
refresh_token                       395        395  OK
renewal_request                       0          0  OK
stocktake_scan                       48         48  OK
stocktake_session                     6          6  OK

Schema version in backup: V25
Uploaded files: 2 in backups/files-20261113-131315.tar.gz, 2 on the server

RESULT: PASSED - the backup restores completely and matches the live data.
```
