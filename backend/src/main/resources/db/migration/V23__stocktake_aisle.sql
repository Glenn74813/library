-- A stocktake can count one aisle of a section; null = the whole section (or the whole collection).
ALTER TABLE stocktake_session ADD COLUMN aisle VARCHAR(20);
