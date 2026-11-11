-- Settings that used to be fixed in code or configuration, now edited in the admin screens.
ALTER TABLE patron_category
    ADD COLUMN max_holds INT NOT NULL DEFAULT 0,               -- active reservations + bookings; 0 = no limit
    ADD COLUMN fine_grace_days INT NOT NULL DEFAULT 0,         -- late open days not charged
    ADD COLUMN max_fine_per_item NUMERIC(10, 2) NOT NULL DEFAULT 0; -- overdue fine cap per loan; 0 = no cap

-- Bumped to invalidate a device's long-lived tokens (security gate middleware).
ALTER TABLE app_user ADD COLUMN token_version INT NOT NULL DEFAULT 0;

INSERT INTO app_setting (key, value) VALUES
    ('library.systemName', 'ZSCMST Smart Library'),
    ('library.name', 'ZSCMST Learning Resource Center'),
    ('library.institution', 'Zamboanga State College of Marine Sciences and Technology'),
    ('library.shortName', 'ZSCMST'),
    ('library.address', 'Fort Pilar, Zamboanga City'),
    ('library.phone', ''),
    ('library.email', ''),
    ('library.website', ''),
    ('library.receiptHeader', ''),
    ('library.receiptFooter', 'Thank you. Please keep this receipt.'),
    ('circulation.bookingHorizonDays', '60'),
    ('circulation.atRiskDays', '2'),
    ('circulation.defaultLostFee', '0'),
    ('circulation.typeLoanDays', ''),
    ('kiosk.opacResetSeconds', '90'),
    ('kiosk.duplicateTapSeconds', '15'),
    ('uploads.ebookMaxMb', '100'),
    ('uploads.audioMaxMb', '10'),
    ('uploads.photoMaxMb', '5')
ON CONFLICT (key) DO NOTHING;
