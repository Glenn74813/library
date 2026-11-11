-- Data Privacy Act of 2012 (RA 10173): erasure requests and retention limits.
ALTER TABLE patron ADD COLUMN anonymized_at TIMESTAMPTZ;

-- Old history past its retention period is re-pointed here, so totals and charts stay correct
-- while no longer identifying anyone.
INSERT INTO patron (card_number, first_name, last_name, category_id, blocked, block_reason, anonymized_at)
SELECT '__ANONYMIZED__', 'Anonymized', 'History', min(id), TRUE, 'System record for anonymized history', now()
FROM patron_category;

INSERT INTO app_setting (key, value) VALUES
    ('privacy.officer', 'Data Protection Officer, ZSCMST'),
    ('privacy.contact', 'dpo@zscmst.edu.ph'),
    ('privacy.notice', 'The ZSCMST Learning Resource Center collects your name, ID or card number, course, contact details, photo, library visits, and borrowing and fine records. We use them only to identify you, lend materials, send due-date and pickup notices, keep the library secure, and produce statistics. Your data is stored on school-controlled servers, is seen only by authorized library staff, and is not shared with third parties except as required by law. Records are kept only for the periods set by the library, after which they are anonymized or deleted. Under the Data Privacy Act of 2012 (RA 10173) you may ask to see, correct, download or erase your data, or object to its processing, by contacting the Data Protection Officer.'),
    ('privacy.retainLoanYears', '0'),
    ('privacy.retainAttendanceYears', '0'),
    ('privacy.retainGateDays', '0'),
    ('privacy.retainNoticeDays', '0'),
    ('privacy.retainAuditYears', '0'),
    ('privacy.anonymizeExpiredYears', '0')
ON CONFLICT (key) DO NOTHING;
