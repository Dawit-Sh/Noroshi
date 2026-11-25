# Noroshi (狼煙)

An Android app for the SIM in your home router: read and send its text
messages, dial USSD codes, and check the signal, network and router status
from your phone while it's on the router's Wi-Fi.

Noroshi is a fork of [frectonz/kimem-android](https://github.com/frectonz/kimem-android),
frectonz's Android port of [kimem](https://github.com/frectonz/kimem), the
Safaricom Kimem MiFi CLI. It keeps kimem's support for that MiFi and adds a
second kind of router.

## Routers

| Router | Panel | Status |
|---|---|---|
| Safaricom Kimem MiFi | ZTE-style, JSON over `/reqproc/proc_get` and `/reqproc/proc_post` | From kimem |
| Stellar 4G CPE (Marvell / ASR "Duster" panel, for example CPE210V3) | Digest login, XML over `/xml_action.cgi` | Status and messages; USSD not working yet |

Pick the router type in Settings, or tap Detect and let the app find out from
the router itself.

## Screenshots

The images in `docs/screenshots` are from kimem and show the MiFi screens.

## License

MIT, see [LICENSE](LICENSE). The original copyright stays with frectonz.
