-- Library barcodes the system assigns to new copies (LRC-000001, LRC-000002, ...). Starts after the highest
-- one already in use, so assigned numbers never repeat an existing label.
CREATE SEQUENCE item_barcode_seq;
SELECT setval('item_barcode_seq',
              GREATEST(1, COALESCE((SELECT max(substring(barcode FROM 5)::bigint) FROM item WHERE barcode ~* '^LRC-[0-9]{6}$'), 0)),
              EXISTS (SELECT 1 FROM item WHERE barcode ~* '^LRC-[0-9]{6}$'));
