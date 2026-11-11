-- Precise shelf location of each copy: section/area (existing "location"), aisle and shelf.
ALTER TABLE item
    ADD COLUMN aisle VARCHAR(20),
    ADD COLUMN shelf VARCHAR(20);
