-- Who closed a reservation and when: released (checked out to the patron), cancelled, or expired.
ALTER TABLE hold_request
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD COLUMN closed_by VARCHAR(60);
