-- A patron may have only one active (PENDING/READY) hold per title, and a copy can be set aside
-- for only one hold. Concurrent requests previously slipped past the application-level check.

-- Clean up duplicates created before this constraint existed: keep the oldest active hold.
UPDATE hold_request h
SET status = 'CANCELLED'
WHERE h.status IN ('PENDING', 'READY')
  AND EXISTS (SELECT 1 FROM hold_request o
              WHERE o.record_id = h.record_id AND o.patron_id = h.patron_id
                AND o.status IN ('PENDING', 'READY') AND o.id < h.id);

UPDATE hold_request h
SET status = 'CANCELLED'
WHERE h.status = 'READY'
  AND EXISTS (SELECT 1 FROM hold_request o
              WHERE o.item_id = h.item_id AND o.status = 'READY' AND o.id < h.id);

CREATE UNIQUE INDEX uq_hold_active_per_patron ON hold_request (record_id, patron_id)
    WHERE status IN ('PENDING', 'READY');

CREATE UNIQUE INDEX uq_hold_ready_item ON hold_request (item_id)
    WHERE status = 'READY';
