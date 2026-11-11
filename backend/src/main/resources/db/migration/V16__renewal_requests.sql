-- Renewals asked for by patrons (portal, self-checkout kiosk, SIP2 stations) wait for staff approval,
-- so a renewal never collides with another patron's reservation or booking unnoticed.
CREATE TABLE renewal_request (
    id           BIGSERIAL PRIMARY KEY,
    loan_id      BIGINT       NOT NULL REFERENCES loan (id) ON DELETE CASCADE,
    patron_id    BIGINT       NOT NULL REFERENCES patron (id),
    status       VARCHAR(20)  NOT NULL,             -- PENDING, APPROVED, REJECTED, CANCELLED
    channel      VARCHAR(20)  NOT NULL,             -- PORTAL, KIOSK, SIP2
    requested_at TIMESTAMPTZ  NOT NULL,
    due_at       TIMESTAMPTZ  NOT NULL,             -- due date when the request was made
    new_due_at   TIMESTAMPTZ,                       -- after approval
    decided_at   TIMESTAMPTZ,
    decided_by   VARCHAR(60),
    reason       VARCHAR(500)
);
CREATE INDEX idx_renewal_status ON renewal_request (status, requested_at);
CREATE INDEX idx_renewal_patron ON renewal_request (patron_id, requested_at);
CREATE UNIQUE INDEX uq_renewal_pending_loan ON renewal_request (loan_id) WHERE status = 'PENDING';

INSERT INTO app_setting (key, value) VALUES
    ('circulation.renewalApproval', 'true'),
    ('notice.template.RENEWAL_APPROVED', 'Hi {name}, your renewal of "{title}" was approved. It is now due on {due}.'),
    ('notice.template.RENEWAL_REJECTED', 'Hi {name}, your renewal of "{title}" was not approved: {reason}. Please return it by {due}.')
ON CONFLICT (key) DO NOTHING;
