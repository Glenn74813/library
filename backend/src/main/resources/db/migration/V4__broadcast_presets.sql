-- Editable announcement presets for the Security gate page (JSON list of {name, message}).
INSERT INTO app_setting (key, value) VALUES ('broadcast.presets', '[
  {"name": "Evacuation", "message": "Attention please. This is an emergency. Please leave the library calmly using the nearest exit."},
  {"name": "Earthquake drill", "message": "This is an earthquake drill. Drop, cover, and hold on. Wait for instructions before leaving the library."},
  {"name": "Fire", "message": "Fire alarm. Please leave the library immediately and calmly using the nearest exit."},
  {"name": "Closing soon", "message": "Good day. The library will close in 15 minutes. Please prepare to check out or return your materials."}
]')
ON CONFLICT (key) DO NOTHING;
