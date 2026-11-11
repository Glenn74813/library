-- Uploaded patron photo (file in storage under this random key; served at /api/opac/photos/{key}).
ALTER TABLE patron ADD COLUMN photo_key VARCHAR(80);
