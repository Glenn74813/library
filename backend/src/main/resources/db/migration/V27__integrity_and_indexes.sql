-- Attendance taps already recorded, by the kiosk's own id for the tap: a tap whose answer was lost and that
-- the kiosk then uploads again from its offline queue is recognised and not recorded twice.
CREATE TABLE attendance_tap (
    client_id VARCHAR(64) PRIMARY KEY,
    seen_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attendance_tap_seen ON attendance_tap (seen_at);

-- Indexes for what the dashboard, returns, reports and scheduled jobs look up every day.
CREATE INDEX idx_loan_checkout ON loan (checkout_at);
CREATE INDEX idx_loan_returned ON loan (returned_at);
CREATE INDEX idx_loan_open_due ON loan (due_at) WHERE returned_at IS NULL;
CREATE INDEX idx_fine_loan ON fine (loan_id, status);
CREATE INDEX idx_fine_settled ON fine (status, settled_at);
CREATE INDEX idx_fine_created ON fine (created_at);
CREATE INDEX idx_attendance_open ON attendance_log (time_in) WHERE time_out IS NULL;
CREATE INDEX idx_gate_event_item ON gate_event (item_id);
CREATE INDEX idx_stocktake_scan_item ON stocktake_scan (item_id);
