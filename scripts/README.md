# GiantBombTV — Plex Sync

`plex-sync.py` turns your Giant Bomb video subscription into a Plex-readable
local library. Each show becomes a "TV series" folder; each video becomes a
`.strm` (the playback URL), a `.nfo` (Kodi-format episode metadata), and a
`-thumb.jpg` (the episode thumbnail). Plex's built-in **Personal Media** /
**Local Media Assets** agent picks all of this up natively — no plugin,
channel, or scanner required.

The script is **idempotent**: re-running only writes new episodes. Schedule
it however you like (cron, systemd timer, NAS task scheduler, Windows
Scheduled Task, GitHub Action against your home server, …) and Plex will
stay in sync.

---

## What you'll need

1. A **Giant Bomb API key** — get it from <https://www.giantbomb.com/api/>
   while logged in. A free key works fine; a premium subscription unlocks
   higher-quality URLs and premium-only videos.
2. **Python 3.8+** *or* **Docker**. Pick whichever you're more comfortable
   with.
3. A folder Plex can read. Most setups already have a "TV Shows" library —
   you can either point Plex at a new folder just for this, or drop a
   `Giant Bomb` subfolder inside an existing TV-shows root.

---

## Option A — Run with Python (simplest)

```bash
# Once:
git clone https://github.com/Clinteastman/GiantBombTV.git
cd GiantBombTV/scripts

# Every time you want to refresh:
python3 plex-sync.py \
    --api-key YOUR_API_KEY_HERE \
    --output /path/to/your/plex/giantbomb
```

Or set the key once in your environment and skip `--api-key`:

```bash
export GIANTBOMB_API_KEY=your_key_here
python3 plex-sync.py --output /path/to/your/plex/giantbomb
```

No third-party dependencies — everything's in the Python standard library.

### Useful flags

| Flag | Default | Description |
| --- | --- | --- |
| `--output` | *required* | Folder Plex's TV library points at. |
| `--api-key` | env `GIANTBOMB_API_KEY` | Your Giant Bomb API key. |
| `--per-show` | `50` | Max episodes to grab per show. |
| `--shows-limit` | `100` | Max shows to enumerate. |
| `--quality` | `high` | `hd` / `high` / `low`. `hd` requires a premium key. |
| `--include-premium` | off | Include premium-locked videos (premium key only). |
| `--active-only` | off | Skip legacy / inactive shows. |
| `--delay-ms` | `100` | Pause between API calls. Bump to 500 if you ever hit a 429. |

Run `python3 plex-sync.py --help` for the canonical list.

---

## Option B — Run with Docker (no Python install needed)

If you already run Plex in Docker, this is probably the path of least
resistance.

### Build the image once

```bash
cd GiantBombTV/scripts
docker build -f Dockerfile.plex-sync -t giantbombtv-plex-sync .
```

### Run it on demand

```bash
docker run --rm \
    -e GIANTBOMB_API_KEY=your_key_here \
    -v /path/to/your/plex/giantbomb:/library \
    giantbombtv-plex-sync \
    --output /library
```

The host folder you mount as `/library` is where Plex should be pointed.
Add `--per-show 100`, `--quality hd`, etc. after the image name as you would
with the bare script.

### docker-compose example

```yaml
services:
  giantbombtv-plex-sync:
    build:
      context: ./GiantBombTV/scripts
      dockerfile: Dockerfile.plex-sync
    image: giantbombtv-plex-sync
    environment:
      GIANTBOMB_API_KEY: "${GIANTBOMB_API_KEY}"
    volumes:
      - /srv/plex/giantbomb:/library
    command: ["--output", "/library", "--per-show", "75"]
    restart: "no"
```

`docker compose run --rm giantbombtv-plex-sync` from there.

---

## Pointing Plex at the output

1. In Plex, **Settings → Manage → Libraries → Add Library**.
2. Pick **TV Shows** as the type.
3. Add the folder you used for `--output` (e.g. `/srv/plex/giantbomb`).
4. **Advanced**:
   - **Scanner**: *Plex TV Series Scanner*
   - **Agent**: *Personal Media* (Plex Pass) **or** the matching local-only
     option in your Plex version. The point is that Plex should read the
     `.nfo` files and not try to match episodes against TheTVDB.
   - Tick **Use local assets** and **Prefer local metadata** if your
     version exposes them.
5. Save. Plex will scan and pick up the shows + episodes + thumbnails.

After every sync run, hit **Scan Library Files** on the library to bring in
new episodes.

---

## Scheduling it

### cron (Linux / macOS)

Refresh every six hours:

```cron
0 */6 * * * GIANTBOMB_API_KEY=your_key /usr/bin/python3 /path/to/plex-sync.py --output /srv/plex/giantbomb >> /var/log/giantbomb-plex-sync.log 2>&1
```

### systemd timer (modern Linux)

`/etc/systemd/system/giantbombtv-plex-sync.service`:

```ini
[Unit]
Description=Giant Bomb → Plex sync

[Service]
Type=oneshot
Environment=GIANTBOMB_API_KEY=your_key
ExecStart=/usr/bin/python3 /path/to/plex-sync.py --output /srv/plex/giantbomb
```

`/etc/systemd/system/giantbombtv-plex-sync.timer`:

```ini
[Unit]
Description=Run Giant Bomb → Plex sync every 6h

[Timer]
OnBootSec=5min
OnUnitActiveSec=6h
Persistent=true

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl enable --now giantbombtv-plex-sync.timer
```

### Synology DSM

**Control Panel → Task Scheduler → Create → Scheduled Task → User-defined
script**. Run user `root` (or one with access to the Plex library), schedule
daily, and use:

```bash
GIANTBOMB_API_KEY=your_key /usr/bin/python3 /volume1/scripts/plex-sync.py --output /volume1/Plex/GiantBomb
```

### Unraid

Install **User Scripts** from Community Apps, drop `plex-sync.py` in, and
schedule it with the cron-style syntax above. Or add the docker image as a
Container with the schedule baked into a wrapper script.

### Windows (Task Scheduler)

Action → "Start a program":

- **Program/script:** `python.exe`
- **Add arguments:** `C:\path\to\plex-sync.py --output D:\Plex\GiantBomb`
- **Start in:** `C:\path\to\` (the scripts folder)

Set `GIANTBOMB_API_KEY` as a user environment variable so the task picks it
up.

### Auto-trigger a Plex scan after each run

Plex's HTTP API can scan a single library on demand. Append this to your
cron / systemd script (replace `<TOKEN>` with your Plex token and `<ID>`
with the library ID — find it in Plex's URL when you click the library):

```bash
curl -fsS "http://localhost:32400/library/sections/<ID>/refresh?X-Plex-Token=<TOKEN>"
```

---

## Troubleshooting

- **"missing API key"** — pass `--api-key` or export `GIANTBOMB_API_KEY`.
- **`HTTP 401`** — bad / expired key. Regenerate it in your Giant Bomb
  profile.
- **`HTTP 429`** — you're polling too aggressively. Bump `--delay-ms 500`
  or run the sync less often.
- **Plex shows the files but no metadata** — your library agent isn't
  reading `.nfo`. Switch the library to *Personal Media* / local-assets
  mode (see "Pointing Plex at the output" above).
- **A `.strm` plays nothing / 403s** — Giant Bomb occasionally rotates URLs
  for premium content. Re-running the sync regenerates fresh `.strm` files.
  If you want stricter freshness, delete `*.strm` before each run; the rest
  (`.nfo`, thumbnails) stays cached.
- **Wrong show folders / weird names** — filenames are slugified to be
  Windows / macOS / Linux safe. Edit `slug()` in `plex-sync.py` if you want
  a different scheme; the script is small and self-contained.

---

## What it actually generates

```
/your/output/
├── Quick Looks/
│   ├── tvshow.nfo
│   ├── poster.jpg
│   ├── Quick Looks - 2026-05-08 - Quick Look- Some Game.strm
│   ├── Quick Looks - 2026-05-08 - Quick Look- Some Game.nfo
│   ├── Quick Looks - 2026-05-08 - Quick Look- Some Game-thumb.jpg
│   └── …
├── Giant Bombcast/
│   └── …
└── …
```

`.strm` files are one-line text files containing the direct MP4 URL — Plex
opens them and streams from Giant Bomb's CDN. No video data is downloaded
to your server.

---

## Reporting bugs

If something breaks, please open an issue on
<https://github.com/Clinteastman/GiantBombTV/issues> with:

- the command you ran
- the output (with your API key redacted)
- your Plex version + agent/scanner choice for the library
