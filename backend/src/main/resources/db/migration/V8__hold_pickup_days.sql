-- How many days a reserved/booked copy waits on the hold shelf, per patron category.
ALTER TABLE patron_category ADD COLUMN hold_pickup_days INT NOT NULL DEFAULT 3;
