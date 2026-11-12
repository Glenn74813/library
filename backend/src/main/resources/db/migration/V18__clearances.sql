-- Library clearance certificates (no loans or unpaid fines), printed on the receipt printer and
-- verifiable later by their reference number.
CREATE TABLE clearance (
    id         BIGSERIAL PRIMARY KEY,
    reference  VARCHAR(30)  NOT NULL UNIQUE,
    patron_id  BIGINT       NOT NULL REFERENCES patron (id),
    purpose    VARCHAR(120),
    issued_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    issued_by  VARCHAR(60)  NOT NULL
);
CREATE INDEX idx_clearance_patron ON clearance (patron_id, issued_at);
