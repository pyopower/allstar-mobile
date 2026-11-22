# Mobile ASL Client

An Android client for [AllStarLink](https://www.allstarlink.org/) — connect to your own
AllStar node over IAX, browse the whole network, link to any node and talk, all from your phone.

*[Versión en español más abajo](#español)*

> Status: **early development (0.1.x)**. Not yet on Google Play.

## How it works

AllStarLink is not a "log in to the network" system like EchoLink: every user has (or has access
to) a **node**. Mobile ASL Client connects to *your* node exactly like the classic **iaxRpt** PC client
(a "radio-less" IAX2 login answered by `rpt(node|X)`), and your node does the linking:

```
phone ──IAX2 (ulaw)──▶ your node ──AllStar links──▶ any node / hub in the network
```

## Features

- Native IAX2 client written in Kotlin: call tokens, MD5 auth, ulaw voice, retransmission, keep-alive
- Push-to-talk using app_rpt's NEWKEY keying (RADIO_KEY / UNKEY), hold or toggle mode, TX time-out
- Receive indicator and level meters (RX / TX), jitter buffer
- Search the full public node list (≈42,000 nodes) by number, callsign, description or location
- Live node status from stats.allstarlink.org: online, on air, links
- Link (`*3`), monitor-only (`*2`), unlink (`*1`), unlink all, DTMF keypad with explained ASL3 commands
- EchoLink nodes: search `EL 123456` and link through your node's EchoLink module
- Favorites and recent nodes, several "my node" profiles
- Runs in the background with a foreground service (Wi-Fi + wake locks)
- External PTT: volume-down key, and broadcast intents `android.intent.action.PTT.down` / `PTT.up`
  (works with [PTT Bridge](https://github.com/pyopower/ptt-bridge) for Bluetooth speaker-mics)
- English and Spanish

## Node setup

### Easy way: one command

Run this on your node (SSH or console). It works on normal ASL installs and on ASL in Docker:

```sh
curl -fsSL https://raw.githubusercontent.com/pyopower/allstar-mobile/main/tools/setup-node.sh | sudo bash
```

It creates a dedicated IAX login with a random password, reloads Asterisk and prints a QR code
(if `qrencode` is installed) and a `mobileasl://` link. Scan the QR with the phone camera, or copy the
link and use **Settings → Set up my node → Paste link** in the app. Run it again to get a new password,
or with `--remove` to delete the login. Options: `--node`, `--container`, `--host`.

### Manual way

Add a dedicated IAX user and dialplan context to your node (ASL3 shown):

`/etc/asterisk/iax.conf`
```ini
[allstarmobile]
type = user
context = allstarmobile
auth = md5
secret = <a long random password>
host = dynamic
disallow = all
allow = ulaw
transfer = no
```

`/etc/asterisk/extensions.conf`
```ini
[allstarmobile]
exten => <your node>,1,rpt(${EXTEN}|X)
```

Then `asterisk -rx "iax2 reload"` and `asterisk -rx "dialplan reload"`, and make sure UDP 4569
reaches the node. In the app: Settings → Add node, with your node number, callsign, host, port,
IAX user and password.

> ⚠️ The stock ASL `iax.conf` ships an `[iaxrpt]` user with a placeholder password
> (`Your_Secret_Pasword_Here`). Change it or remove it: anyone who knows it can control your node.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36).

```sh
./gradlew assembleDebug
```

## License

GPL-3.0-or-later. Mobile ASL Client is an independent project, not affiliated with AllStarLink, Inc.
Node list and statistics come from the public allstarlink.org services.

---

## Español

Cliente Android para AllStarLink: se conecta a **tu propio nodo** por IAX (igual que iaxRpt),
busca en toda la red, enlaza con cualquier nodo y habla desde el móvil.

- Cliente IAX2 propio en Kotlin (token de llamada, MD5, ulaw, retransmisión)
- PTT mantenido o conmutado con el protocolo NEWKEY de app_rpt, tiempo máximo de TX
- Buscador de los ≈42.000 nodos públicos por número, indicativo, descripción o lugar
- Estado en vivo (stats.allstarlink.org), enlazar `*3`, solo escuchar `*2`, desenlazar `*1`, desenlazar todo, teclado DTMF con comandos explicados, nodos EchoLink (`EL 123456`)
- Favoritos, recientes y varios perfiles de nodo
- PTT externo: tecla de bajar volumen e intents `android.intent.action.PTT.down` / `PTT.up`

**Configurar el nodo con un solo comando** (instalación normal o Docker), en el nodo por SSH:

```sh
curl -fsSL https://raw.githubusercontent.com/pyopower/allstar-mobile/main/tools/setup-node.sh | sudo bash
```

Crea el acceso, muestra un QR y un enlace `mobileasl://` que añade el nodo a la app (Ajustes → Configurar mi nodo → Pegar enlace).
La configuración manual es la del apartado *Node setup*. Software libre GPL-3.0, sin relación con AllStarLink, Inc.
