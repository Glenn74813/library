-- Advance bookings: a hold for a chosen pickup date. Until that date (status SCHEDULED) copies keep
-- circulating; loans that would collide with a booking are shortened.
ALTER TABLE hold_request ADD COLUMN pickup_date DATE;

DROP INDEX uq_hold_active_per_patron;
CREATE UNIQUE INDEX uq_hold_active_per_patron ON hold_request (record_id, patron_id)
    WHERE status IN ('SCHEDULED', 'PENDING', 'READY');

CREATE INDEX idx_hold_booking ON hold_request (record_id, pickup_date) WHERE status = 'SCHEDULED';
