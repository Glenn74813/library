-- Offline desk transactions already applied, by the id the desk gave them, so a re-sent queue (e.g. the
-- reply to an upload was lost) is not applied twice.
CREATE TABLE offline_applied (
    id         VARCHAR(64) PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
