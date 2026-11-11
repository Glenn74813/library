-- Staff accounts whose password was set by an administrator must choose their own at next sign-in.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- One-time codes e-mailed to patrons who forgot their PIN.
CREATE TABLE pin_reset (
    id         BIGSERIAL PRIMARY KEY,
    patron_id  BIGINT      NOT NULL REFERENCES patron (id) ON DELETE CASCADE,
    code_hash  VARCHAR(200) NOT NULL,
    attempts   INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE INDEX idx_pin_reset_patron ON pin_reset (patron_id, created_at);
