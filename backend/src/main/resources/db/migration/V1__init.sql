-- ZSCMST Smart Library schema

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(60)  NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_token (
    id           BIGSERIAL PRIMARY KEY,
    token_hash   VARCHAR(128) NOT NULL UNIQUE,
    subject_type VARCHAR(10)  NOT NULL,
    subject_id   BIGINT       NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_subject ON refresh_token (subject_type, subject_id);

CREATE TABLE patron_category (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(60)    NOT NULL UNIQUE,
    loan_days        INT            NOT NULL,
    max_loans        INT            NOT NULL,
    max_renewals     INT            NOT NULL,
    fine_per_day     NUMERIC(10, 2) NOT NULL,
    max_unpaid_fines NUMERIC(10, 2) NOT NULL
);

CREATE TABLE course (
    id   BIGSERIAL PRIMARY KEY,
    code VARCHAR(30)  NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL
);

CREATE TABLE patron (
    id           BIGSERIAL PRIMARY KEY,
    card_number  VARCHAR(40)  NOT NULL UNIQUE,
    rfid_uid     VARCHAR(64) UNIQUE,
    first_name   VARCHAR(80)  NOT NULL,
    last_name    VARCHAR(80)  NOT NULL,
    email        VARCHAR(150),
    phone        VARCHAR(40),
    category_id  BIGINT       NOT NULL REFERENCES patron_category (id),
    course_id    BIGINT REFERENCES course (id),
    photo_url    VARCHAR(500),
    expiry_date  DATE,
    blocked      BOOLEAN      NOT NULL DEFAULT FALSE,
    block_reason VARCHAR(255),
    pin_hash     VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_patron_name ON patron (lower(last_name), lower(first_name));

CREATE TABLE bib_record (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(500) NOT NULL,
    subtitle         VARCHAR(500),
    authors          VARCHAR(500),
    isbn             VARCHAR(20),
    publisher        VARCHAR(255),
    publication_year INT,
    edition          VARCHAR(60),
    subjects         VARCHAR(1000),
    call_number      VARCHAR(100),
    material_type    VARCHAR(20)  NOT NULL,
    language         VARCHAR(40),
    summary          TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    search_vector    TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(subtitle, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(authors, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(subjects, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(publisher, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(summary, '')), 'D')
    ) STORED
);
CREATE INDEX idx_bib_search ON bib_record USING GIN (search_vector);
CREATE INDEX idx_bib_isbn ON bib_record (isbn);
CREATE INDEX idx_bib_call_number ON bib_record (call_number);

CREATE TABLE item (
    id            BIGSERIAL PRIMARY KEY,
    record_id     BIGINT      NOT NULL REFERENCES bib_record (id) ON DELETE CASCADE,
    barcode       VARCHAR(50) NOT NULL UNIQUE,
    rfid_epc      VARCHAR(64) UNIQUE,
    location      VARCHAR(100),
    status        VARCHAR(20) NOT NULL,
    acquired_date DATE,
    price         NUMERIC(12, 2),
    notes         VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_item_record ON item (record_id);

CREATE TABLE loan (
    id             BIGSERIAL PRIMARY KEY,
    item_id        BIGINT      NOT NULL REFERENCES item (id),
    patron_id      BIGINT      NOT NULL REFERENCES patron (id),
    checkout_at    TIMESTAMPTZ NOT NULL,
    due_at         TIMESTAMPTZ NOT NULL,
    returned_at    TIMESTAMPTZ,
    renewals       INT         NOT NULL DEFAULT 0,
    checked_out_by VARCHAR(60)
);
CREATE INDEX idx_loan_patron ON loan (patron_id);
CREATE INDEX idx_loan_item ON loan (item_id);
CREATE UNIQUE INDEX uq_loan_active_item ON loan (item_id) WHERE returned_at IS NULL;

CREATE TABLE hold_request (
    id         BIGSERIAL PRIMARY KEY,
    record_id  BIGINT      NOT NULL REFERENCES bib_record (id) ON DELETE CASCADE,
    patron_id  BIGINT      NOT NULL REFERENCES patron (id),
    item_id    BIGINT REFERENCES item (id),
    status     VARCHAR(20) NOT NULL,
    placed_at  TIMESTAMPTZ NOT NULL,
    ready_at   TIMESTAMPTZ,
    expires_at TIMESTAMPTZ
);
CREATE INDEX idx_hold_record ON hold_request (record_id, status);
CREATE INDEX idx_hold_patron ON hold_request (patron_id);

CREATE TABLE fine (
    id             BIGSERIAL PRIMARY KEY,
    patron_id      BIGINT         NOT NULL REFERENCES patron (id),
    loan_id        BIGINT REFERENCES loan (id),
    amount         NUMERIC(10, 2) NOT NULL,
    reason         VARCHAR(255)   NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    settled_at     TIMESTAMPTZ,
    receipt_number VARCHAR(40),
    settled_by     VARCHAR(60)
);
CREATE INDEX idx_fine_patron ON fine (patron_id, status);
CREATE INDEX idx_fine_receipt ON fine (receipt_number);

CREATE TABLE notice (
    id         BIGSERIAL PRIMARY KEY,
    patron_id  BIGINT        NOT NULL REFERENCES patron (id),
    type       VARCHAR(20)   NOT NULL,
    message    VARCHAR(1000) NOT NULL,
    reference  VARCHAR(60)   NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ,
    UNIQUE (type, reference)
);

CREATE TABLE attendance_log (
    id        BIGSERIAL PRIMARY KEY,
    patron_id BIGINT      NOT NULL REFERENCES patron (id),
    time_in   TIMESTAMPTZ NOT NULL,
    time_out  TIMESTAMPTZ,
    method    VARCHAR(20) NOT NULL
);
CREATE INDEX idx_attendance_time ON attendance_log (time_in);
CREATE INDEX idx_attendance_patron ON attendance_log (patron_id, time_in);

CREATE TABLE app_setting (
    key   VARCHAR(80) PRIMARY KEY,
    value TEXT
);

CREATE TABLE gate_event (
    id          BIGSERIAL PRIMARY KEY,
    gate_id     VARCHAR(40) NOT NULL,
    event_type  VARCHAR(20) NOT NULL,
    item_id     BIGINT REFERENCES item (id) ON DELETE SET NULL,
    epc         VARCHAR(64),
    visitor_count INT,
    message     VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_gate_time ON gate_event (occurred_at);

CREATE TABLE stocktake_session (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    location   VARCHAR(100),
    started_by VARCHAR(60),
    started_at TIMESTAMPTZ  NOT NULL,
    closed_at  TIMESTAMPTZ
);

CREATE TABLE stocktake_scan (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES stocktake_session (id) ON DELETE CASCADE,
    code       VARCHAR(64) NOT NULL,
    item_id    BIGINT REFERENCES item (id) ON DELETE SET NULL,
    scanned_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, code)
);

CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    actor      VARCHAR(60)  NOT NULL,
    action     VARCHAR(60)  NOT NULL,
    details    VARCHAR(1000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_time ON audit_log (created_at);

-- Defaults
INSERT INTO patron_category (name, loan_days, max_loans, max_renewals, fine_per_day, max_unpaid_fines) VALUES
    ('Student', 7, 3, 1, 5.00, 100.00),
    ('Faculty', 30, 10, 3, 0.00, 500.00),
    ('Staff', 14, 5, 2, 5.00, 200.00),
    ('Alumni', 7, 2, 1, 10.00, 50.00);

INSERT INTO app_setting (key, value) VALUES
    ('kiosk.mode', 'ATTENDANCE'),
    ('kiosk.singleTap', 'false'),
    ('kiosk.entryMessage', 'Welcome to the ZSCMST Learning Resource Center!'),
    ('kiosk.exitMessage', 'Thank you for visiting. See you again!'),
    ('kiosk.standbyMessages', 'Library hours: Mon-Fri 7:30 AM - 6:00 PM|Please keep quiet inside the library|Return borrowed books on or before the due date'),
    ('kiosk.videoUrl', ''),
    ('notice.template.DUE_SOON', 'Hi {name}, "{title}" is due on {due}. Please return or renew it on time.'),
    ('notice.template.OVERDUE', 'Hi {name}, "{title}" was due on {due} and is now overdue. Fines may apply.'),
    ('notice.template.HOLD_READY', 'Hi {name}, your reserved item "{title}" is ready for pickup until {due}.'),
    ('gate.emergencyMode', 'false'),
    ('gate.alarmEnabled', 'true');
