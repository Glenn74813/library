# Kiosk Lockdown Guide

How to set up the OPAC kiosks and the attendance kiosk so the public can use only the library
screen. Do this on each kiosk PC after the library server is running over HTTPS.

| Kiosk | Address to open |
|-------|-----------------|
| OPAC (catalog search) | `https://<server>/opac?kiosk=1` |
| Attendance | `https://<server>/kiosk/attendance` |
| Self-checkout | `https://<server>/kiosk/selfcheck` |

## 1. Accounts and sign-in

1. In **Users & devices → Add device**, create one KIOSK account per kiosk (e.g. `kiosk-opac-1`).
2. On the kiosk, open the attendance or self-checkout address once and sign in with that account. It stays
   signed in and renews its own session. (The OPAC kiosk needs no sign-in.)

## 2. Windows 10/11: Assigned Access (recommended)

1. Create a local standard Windows user, e.g. `kiosk`, with no admin rights.
2. **Settings → Accounts → Other users → Set up a kiosk** (Assigned access) → choose the `kiosk`
   user → app **Microsoft Edge** → *As a digital sign or interactive display* (attendance) or
   *As a public browser* (OPAC) → enter the kiosk address.
3. Set *Restart after idle time* to 2 minutes for the OPAC (the page also resets itself after 90 seconds).
4. Sign out and sign in as `kiosk`: Edge opens full screen; Ctrl+Alt+Del is the only way out.

Alternatively, Edge kiosk mode from a shortcut:

```
msedge.exe --kiosk https://<server>/opac?kiosk=1 --edge-kiosk-type=public-browsing --no-first-run
```

For the self-checkout kiosk with a receipt printer, set the printer as Windows' default and add
`--kiosk-printing` so the due-date slip prints without a dialog.

## 3. Linux (Ubuntu) with Chromium

1. Create a user `kiosk` with auto-login (Settings → Users → Automatic Login).
2. Disable screen lock, sleep and notifications for that user.
3. Add `~/.config/autostart/kiosk.desktop`:

```
[Desktop Entry]
Type=Application
Name=Library kiosk
Exec=chromium --kiosk --noerrdialogs --disable-infobars --no-first-run --disable-translate --overscroll-history-navigation=0 --disable-pinch https://<server>/opac?kiosk=1
```

4. Optional: install `unclutter` to hide the mouse pointer on touch screens.

## 4. Browser policies (both systems)

Apply through Group Policy (Windows) or `/etc/chromium/policies/managed/kiosk.json` (Linux):

```json
{
  "DeveloperToolsAvailability": 2,
  "DownloadRestrictions": 3,
  "PrintingEnabled": false,
  "PasswordManagerEnabled": false,
  "AutofillAddressEnabled": false,
  "AutofillCreditCardEnabled": false,
  "BrowserAddPersonEnabled": false,
  "BrowserGuestModeEnabled": false,
  "IncognitoModeAvailability": 1,
  "URLAllowlist": ["https://<server>"],
  "URLBlocklist": ["*"],
  "AudioCaptureAllowedUrls": [],
  "AutoplayAllowed": true
}
```

`AutoplayAllowed` lets announcements and the attendance kiosk's video play without a click.
For the attendance kiosk do **not** clear browsing data on exit: its offline queue and sign-in are kept there.

## 5. Hardware and power

- RFID card readers and barcode scanners must be in **keyboard (HID) mode**; test them in Notepad first.
- BIOS: *Restore on AC power loss = Power On*, so kiosks start after a brown-out.
- Windows: Power options → never sleep, screen never off (attendance); OPAC screen may turn off after 30 min.
- Disable Windows Update active hours restarts during library hours.
- Lock the PC case and cover unused USB ports; keep keyboards out of reach of the public where possible.

## 6. Check

- [ ] Kiosk starts straight into the library page after a power cut.
- [ ] Alt+Tab, Alt+F4, Windows key, right-click menu and F12 do nothing useful.
- [ ] Typing another address is impossible or blocked.
- [ ] Card reader / scanner works on the kiosk page.
- [ ] Announcements play sound.
- [ ] Attendance kiosk keeps working (offline queue) when the network cable is unplugged, and syncs after.
