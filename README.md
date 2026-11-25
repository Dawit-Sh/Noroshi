# Noroshi (狼煙)

An Android app for the SIM in your home router: read and send its text
messages, dial USSD codes, and check the signal, network and router status
from your phone while it's on the router's Wi-Fi.

Noroshi is a fork of [frectonz/kimem-android](https://github.com/frectonz/kimem-android),
frectonz's Android port of [kimem](https://github.com/frectonz/kimem), the
Safaricom Kimem MiFi CLI. It keeps kimem's support for that MiFi and adds a
second kind of router.

## Archived

This repository is archived and no longer worked on. It stays public in case
the code is useful to someone with a similar router: fork it and carry on.
What it reached:

- On the Stellar: status (system, signal, internet, connected devices,
  reboot), messages (list, read, send, delete, mark read, across the device
  inbox, sent, SIM and drafts), and data use against a bundle using the
  router's own counter, with alerts at 50/75/90/100% and a home screen widget.
- The router calls were checked against a real Stellar (firmware
  `CPE210V3_EU-V5.3.1`), and the tests replay its scrubbed answers. The app
  itself was only run on an Android 17 emulator against a stand-in
  that replays those answers, never on a real phone.
- Android 17 blocks apps from the local network unless they ask for the new
  local network permission; the app asks for it.

Known limits of the Stellar firmware, not of the app:

- USSD never answers. The firmware accepts the request (`file=ussd`) but
  nothing comes back, on 4G or 3G, so the app hides USSD for this router.
- The device inbox counts some incoming texts without ever listing them
  (likely the operator's own messages). They fill the store until it reads
  "full" and hides everything; only a factory reset clears the count. Saving
  texts to the SIM instead avoids it.
- No newer official firmware for this router could be found.

## Routers

| Router | Panel | Status |
|---|---|---|
| Safaricom Kimem MiFi | ZTE-style, JSON over `/reqproc/proc_get` and `/reqproc/proc_post` | From kimem |
| Stellar 4G CPE (Marvell / ASR "Duster" panel, for example CPE210V3) | Digest login, XML over `/xml_action.cgi` | Status, messages and data use; USSD doesn't work on its firmware |

Pick the router type in Settings, or tap Detect and let the app find out from
the router itself.

## Screenshots

The images in `docs/screenshots` are from kimem and show the MiFi screens.

## License

MIT, see [LICENSE](LICENSE). The original copyright stays with frectonz.
