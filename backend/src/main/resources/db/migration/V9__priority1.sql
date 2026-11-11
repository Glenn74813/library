-- Library calendar: weekly opening hours (NULL times = closed that weekday) and closed dates.
CREATE TABLE library_hours (
    weekday    INT PRIMARY KEY CHECK (weekday BETWEEN 1 AND 7),   -- 1 = Monday ... 7 = Sunday
    open_time  TIME,
    close_time TIME
);
INSERT INTO library_hours (weekday, open_time, close_time) VALUES
    (1, '07:30', '18:00'), (2, '07:30', '18:00'), (3, '07:30', '18:00'), (4, '07:30', '18:00'),
    (5, '07:30', '18:00'), (6, '08:00', '12:00'), (7, NULL, NULL);

CREATE TABLE closed_day (
    day    DATE PRIMARY KEY,
    reason VARCHAR(120) NOT NULL
);

-- Room use only: NULL = follow the material-type rule, TRUE/FALSE = override for this copy.
ALTER TABLE item ADD COLUMN room_use_only BOOLEAN;

-- Login protection.
ALTER TABLE app_user ADD COLUMN failed_logins INT NOT NULL DEFAULT 0, ADD COLUMN locked_until TIMESTAMPTZ;
ALTER TABLE patron ADD COLUMN pin_failures INT NOT NULL DEFAULT 0, ADD COLUMN pin_locked_until TIMESTAMPTZ;

-- Notice delivery results.
ALTER TABLE notice ADD COLUMN channels VARCHAR(40), ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error VARCHAR(300);

INSERT INTO app_setting (key, value) VALUES
    ('circulation.roomUseTypes', 'REFERENCE,THESIS'),
    ('security.maxAttempts', '5'),
    ('security.lockMinutes', '15'),
    ('notice.sendHour', '7'),
    ('notice.dueSoonDays', '2'),
    ('notice.lastRun', ''),
    ('mail.enabled', 'false'), ('mail.host', ''), ('mail.port', '587'), ('mail.username', ''),
    ('mail.password', ''), ('mail.from', ''), ('mail.starttls', 'true'),
    ('sms.enabled', 'false'), ('sms.url', ''), ('sms.apiKey', ''), ('sms.sender', 'ZSCMST-LRC')
ON CONFLICT (key) DO NOTHING;
