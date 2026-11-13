# RFID bridge: connecting any RFID reader or security gate

The library system works with RFID hardware from any vendor. Readers come in many forms, so a small program,
the **RFID bridge**, runs on the PC next to each reader and translates between the reader and the library:

```
 RFID reader / pad / gate  ──►  RFID bridge (this program, on that PC)  ──►  library pages / library server
   serial, USB-COM, TCP/IP,          reads tags, writes tags,                  security off at checkout,
   vendor CLI or SDK, push           switches security, checks the gate         on at return; gate alarms
```

It is one Java file with no other software to install. It needs **Java 21 or later** (free, e.g. Eclipse
Temurin) on that PC.

```
java RfidBridge.java                      # uses rfid-bridge.properties in the same folder
java RfidBridge.java gate.properties      # or a named settings file
java RfidBridge.java --reader=simulator   # any setting can also be given on the command line
```

Start it automatically with Windows (Task Scheduler, "At log on", or a shortcut in `shell:startup`).

## Which readers work

| Reader connects by | Setting | Typical hardware |
|---|---|---|
| **Keyboard wedge** (types the tag like a keyboard) | none: no bridge needed | USB desktop pads in "keyboard" mode, barcode scanners |
| **Serial / USB-COM** (sends tag numbers as text) | `reader=serial`, `serial.device=COM3` | most USB desktop UHF readers, many gate controllers |
| **TCP/IP** (network reader in text mode) | `reader=tcp`, `tcp.host`, `tcp.port` | network UHF readers and gates |
| **Vendor command-line tool / SDK sample** | `reader=exec`, `exec.inventory=...` | any reader with a vendor SDK |
| **Push** (the reader's own software sends reads) | `reader=push`; it POSTs to `/push` | reader software with an HTTP "webhook" |
| **Simulator** (no hardware) | `reader=simulator` | testing and training |

Tag numbers are picked out of each line the reader sends with `epc.pattern` (hex EPCs of 16–32 digits by
default), so the reader's output format doesn't matter.

**Writing tags and switching security** (the EAS bit or AFI that the gate checks) needs the reader's own
write function. Point `writer=exec` at the vendor's command-line tool or SDK sample:

```
writer=exec
exec.write=C:/Vendor/cli.exe write-epc --epc {epc}
exec.securityOn=C:/Vendor/cli.exe eas --epc {epc} --set 1
exec.securityOff=C:/Vendor/cli.exe eas --epc {epc} --set 0
```

The command must exit with code 0 when it works. `{epc}` is the tag number; `{barcode}` the copy's barcode;
`{on}` is 1/0 and `{secure}` true/false. A new tag's EPC is the copy's barcode written as hex (ASCII), padded to
96 bits (128 bits for barcodes longer than 12 characters), so any reader can turn a tag back into the barcode.

See `station.properties.example` and `gate.properties.example` for every setting.

## Circulation desk and self-check kiosk (mode=station)

1. Install Java, copy this folder to the PC, copy `station.properties.example` to `rfid-bridge.properties`,
   and set the reader (and writer) for your hardware.
2. Run the bridge and leave it running.
3. **Circulation desk:** Check out / in → **RFID station on this computer** → tick *Use the RFID bridge*,
   press **Test**.
   **Self-check kiosk:** add `&rfid=http://127.0.0.1:8765` to the kiosk's setup link
   (`/kiosk/selfcheck#key=kd_…&rfid=http://127.0.0.1:8765`).

Then:

- **Checkout** (desk, kiosk, releasing a hold) switches the copy's tag security **off**.
- **Return** (desk, kiosk, patron page) switches it **on**.
- **Copy form** gets **Read** (fills the RFID EPC from the tag on the pad) and **Write** (writes the copy's
  barcode to a new tag).
- If the station can't switch a tag, the desk log says so, and the kiosk tells the patron to show the item at
  the desk before leaving.

The bridge only listens on `127.0.0.1` (this PC) unless `bind=` is changed.

### Bridge API (for reader software developers)

| Request | Body | Answer |
|---|---|---|
| `GET /status` | | `{"ok":true,"mode","reader","writer","tags":N}` |
| `GET /tags` | | `{"tags":["EPC", ...]}` tags on the pad now |
| `POST /write` | `{"barcode":"LRC-000123"}` | `{"epc":"4C52432D..."}` |
| `POST /security` | `{"epc":"...","secure":true\|false}` | `{"ok":true}` |
| `POST /push` | `{"epcs":["EPC", ...]}` | `{"ok":true}` reads from any software |
| `POST /event` | `{"type":"VISITOR","visitorCount":1}` | `{"ok":true}` (gate mode) |

Errors answer HTTP 409 with `{"error":"..."}`.

## Security gate (mode=gate)

1. **Administration → Users & devices → Add device → Security gate**, and copy its **device token**.
2. On the gate PC: copy `gate.properties.example` to `rfid-bridge.properties`, set `lms.url`,
   `lms.deviceKey` (the token), `gate.id` and the reader, and optionally `gate.alarm` (the command that sounds the gate's
   alarm or relay).
3. Run the bridge.

Every tag passing the antennas is checked with the library in real time: an item that is not borrowed is an
**ALARM** event on the **Security gate** page (with title and barcode), and `gate.alarm` runs, except in
emergency mode or outside the alarm schedule. A **HEARTBEAT** every minute shows the gate online with its
"last seen" time. Visitor counts, tamper and power events from the gate's own software go to `POST /event`.

Gates that sound by themselves from the tag's EAS bit work too: the stations switch the bit at checkout and
return, and the bridge still reports reads and alarms to the library.

### Library server API used by gates

Gates may also call the library directly instead of through the bridge, sending the gate's device token as
`Authorization: Bearer <token>`:

| Request | Body | Answer |
|---|---|---|
| `GET /api/gate/device/config` | | alarm on/off, schedule, emergency mode, armed now |
| `POST /api/gate/device/check` | `{"gateId":"GATE-1","epcs":["..."]}` | `{"alarm":bool,"emergencyMode":bool,"tags":[{epc,barcode,title,status,authorized}]}` |
| `POST /api/gate/device/events` | `{"gateId","type","visitorCount","message"}` | 204; types ALARM, TAMPER, POWER_LOSS, POWER_RESTORED, VISITOR, EMERGENCY_ON, EMERGENCY_OFF, HEARTBEAT |

Or over **SIP2** (TCP port 6001): Item Information (17) gives each item's circulation status.

## Choosing hardware

Ask the vendor for one of these for every reader, pad and gate:

- a **serial/USB-COM or TCP text output** of the tags read, or an **HTTP push**, or a **command-line tool**; and
- for pads and tagging stations: a command-line tool or SDK sample that can **write the EPC** and **set the EAS
  bit / AFI** of a tag (ISO 18000-6C, EPC Class 1 Gen 2).

With either, the reader works with this library system without changes to the system.
