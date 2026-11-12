-- Every document printed for a patron or the desk (receipt-printer slips, official receipts, ID cards,
-- clearances, cash reports), so staff can see what was already given out and spot reprints.
CREATE TABLE print_log (
    id          BIGSERIAL PRIMARY KEY,
    kind        VARCHAR(30)  NOT NULL,
    patron_id   BIGINT       REFERENCES patron (id),
    reference   VARCHAR(60),
    details     VARCHAR(300),
    copy_number INT          NOT NULL DEFAULT 1,
    printed_by  VARCHAR(60)  NOT NULL,
    printed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_print_log_patron ON print_log (patron_id, printed_at);
CREATE INDEX idx_print_log_time ON print_log (printed_at);
CREATE INDEX idx_print_log_ref ON print_log (kind, reference);

-- Clearances issued before this log existed were printed when issued.
INSERT INTO print_log (kind, patron_id, reference, details, printed_by, printed_at)
SELECT 'CLEARANCE', patron_id, reference, purpose, issued_by, issued_at FROM clearance;
