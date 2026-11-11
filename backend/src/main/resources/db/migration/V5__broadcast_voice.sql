-- Text-to-speech voice used by kiosks/displays for announcements. Kiosks use the named voice if it
-- is installed on that device, otherwise the first installed voice for the language.
INSERT INTO app_setting (key, value) VALUES
    ('broadcast.voice.name', ''),
    ('broadcast.voice.lang', 'en-US'),
    ('broadcast.voice.rate', '0.95'),
    ('broadcast.voice.pitch', '1'),
    ('broadcast.voice.repeat', '2')
ON CONFLICT (key) DO NOTHING;
