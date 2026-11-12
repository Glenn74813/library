-- Kiosks and gates connect with a device key (sent as X-Device-Key) instead of a password and an
-- expiring session. Only the SHA-256 hash is stored; the key itself is shown once when issued.
ALTER TABLE app_user
    ADD COLUMN device_key_hash VARCHAR(64) UNIQUE,
    ADD COLUMN device_key_issued_at TIMESTAMPTZ,
    ADD COLUMN device_last_seen_at TIMESTAMPTZ;
