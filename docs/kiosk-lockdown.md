# Kiosk Lockdown Guide

How to set up the OPAC kiosks and the attendance kiosk so the public can use only the library
screen. Do this on each kiosk PC after the library server is running over HTTPS.

| Kiosk | Address to open |
|-------|-----------------|
| OPAC (catalog search) | `https://<server>/opac` |
| Attendance | `https://<server>/kiosk/attendance` |
| Self-checkout | `https://<server>/kiosk/selfcheck` |

## 1. Connect the kiosk (device key)

1. In **Users & devices → Add device**, choose **Kiosk** and give it a name (e.g. `Lobby attendance kiosk`).
   After saving, the dialog shows the kiosk's **device key** and two **setup links** (attendance and self-checkout).
2. On the kiosk PC, open the matching setup link once. The kiosk saves the key and starts; nobody types a password.
   (Or open the kiosk page and paste the key.) The OPAC search kiosk needs no key.
3. The kiosk stays connected until an administrator **replaces** or **revokes** its key or disables the device;
   then it returns to its setup screen. **Last connected** in the device list shows when each kiosk was last online.
4. To disconnect a kiosk from the kiosk itself, hold the top-left corner of its screen for 5 seconds and confirm.

Kiosks set up earlier with a kiosk username and password keep working (the setup screen still offers
"Sign in with a kiosk account instead").

## 2. Windows 10/11: Assigned Access (recommended)

1. Create a local standard Windows user, e.g. `kiosk`, with no admin rights.
2. **Settings → Accounts → Other users → Set up a kiosk** (Assigned access) → choose the `kiosk`
   user → app **Microsoft Edge** → *As a digital sign or interactive display* (attendance) or
   *As a public browser* (OPAC) → enter the kiosk address.
3. Set *Restart after idle time* to 2 minutes for the OPAC (the page also resets itself after 90 seconds).
4. Sign out and sign in as `kiosk`: Edge opens full screen; Ctrl+Alt+Del is the only way out.

Alternatively, Edge kiosk mode from a shortcut:

```
msedge.exe --kiosk https://<server>/opac --edge-kiosk-type=public-browsing --no-first-run
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
Exec=chromium --kiosk --noerrdialogs --disable-infobars --no-first-run --disable-translate --overscroll-history-navigation=0 --disable-pinch https://<server>/opac
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
