-- Scans look codes up case-insensitively, so codes differing only in case must not both exist
-- (a lookup would match two rows and fail).
CREATE UNIQUE INDEX uq_patron_card_ci ON patron (lower(card_number));
CREATE UNIQUE INDEX uq_patron_rfid_ci ON patron (lower(rfid_uid)) WHERE rfid_uid IS NOT NULL;
CREATE UNIQUE INDEX uq_item_barcode_ci ON item (lower(barcode));
CREATE UNIQUE INDEX uq_item_epc_ci ON item (lower(rfid_epc)) WHERE rfid_epc IS NOT NULL;
