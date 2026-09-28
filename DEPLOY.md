# Deploying DallyControl

<sub>[← README](README.md) · **Deploy** · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md)</sub>

Three ways to run it. All generate secrets and a working admin login — no default passwords, and the
admin is forced to set its own password on first login.

## Option A — one line, no clone (published images)

The fastest path: pull the released images from GHCR — no clone, no build. Needs only Docker + `curl`.

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/david0ql/MDMesh/main/quickstart.sh)
```

It creates `./dallycontrol`, downloads the pull-only compose (`docker-compose.release.yml`) + seed, generates
secrets, `docker compose pull && up -d`, seeds, and prints the console URL + a temporary admin password.

It pins the latest published release: `SERVER_VERSION`, `WEB_VERSION` and `CURRENT_VERSION` in `.env` all name that
version (e.g. `0.3.1`), so the console doesn't offer the release you just installed as an update, and the compose file +
seed are downloaded from that release's tag (`v0.3.1`) so they match the images. The supervisor
tracks `SUPERVISOR_VERSION=latest`, so `docker compose pull` keeps delivering its fixes (updates never touch it); pin it
only if you want to freeze it. If the GitHub API can't be reached (or is rate-limited) the quick start falls back to the
`:latest` images and the `main` compose + seed with `CURRENT_VERSION=0.0.0`: the install works, but the console shows "Update available" until the
first update, which pins the versions (or set `SERVER_VERSION`/`WEB_VERSION`/`CURRENT_VERSION` to the running release by hand).

> **Requires a published release**, and the GHCR packages (`dallycontrol-server`/`-web`/`-supervisor`) must be
> **public** — or run `docker login ghcr.io` first. See [RELEASING.md](RELEASING.md).

> **Upgrading a from-source Docker install made with `./setup.sh` between v0.2.2 and v0.2.6?** A bug in the
> seed gate meant those installs kept the stock `admin` / `admin` login and never enabled QR/token enrollment
> defaults. After `git pull`, re-run `./setup.sh` (it applies the repairs idempotently) and **change the admin
> password** from the console if you never did. Quick-start (`quickstart.sh`) installs got a random password
> but also missed the enrollment defaults; re-running `./setup.sh` in a clone fixes that too. Fixed in v0.2.7.

> **Upgrading to v0.3.0?** Desired-state configuration ships in this release: on its first check-in after the
> upgrade, every device whose agent supports it applies its assigned configuration's managed policies, and
> any device on a configuration with kiosk mode on enters kiosk. The upgrade itself never lifts kiosk on a
> device — only turning kiosk off in that device's configuration does. Older agents are unaffected and show
> "agent too old" in the console instead of receiving the new command. Location capture mode also follows the
> configuration after the upgrade (GPS → active, otherwise passive), so an ad-hoc `device.locationMode` override
> is replaced.

> **Docker: supervisor restarting with `Cannot find module '/project/server.js'`?** Every Docker install from v0.1.0
> through v0.3.0 hit this (#27), so Settings → Updates, the `/recovery` page and the Docker `/files/agent.apk` mirror
> (the APK the enrollment QR points to) never worked. Fixed in v0.3.1 in the image itself — your existing compose file
> works unchanged. Quick-start installs: `docker compose pull && docker compose up -d` (if `.env` pins
> `SUPERVISOR_VERSION`, set it to `0.3.1` first). From-source installs: `git pull` and re-run `./setup.sh` (if you
> removed `working_dir` by hand, `git checkout -- docker-compose.yml` first). The supervisor never updates itself, so
> this manual pull is how it picks up fixes. What you get back depends on the install: quick-start installs get the
> update banner, one-click **Update** and the `/recovery` **Roll back** button; from-source Docker installs
> (`APPLY_SUPPORTED=0`) get the update banner (its **Details** link leads to the manual steps in Settings) and a `/recovery` page that shows status and the manual update steps
> instead of Roll back, since one-click apply and rollback aren't supported there.

## Option B — from source (clone + build)

Prereqs: Docker + Compose v2, and `openssl`.

```bash
git clone https://github.com/david0ql/MDMesh.git && cd MDMesh
./setup.sh
```

The wizard asks how you want to expose it:

- **Cloudflare Tunnel** — no open ports; Cloudflare manages TLS. You need a domain in a Cloudflare
  account. Create a tunnel (Zero Trust → Networks → Tunnels), route its public hostname to
  `http://caddy:80`, and paste the tunnel token when prompted.
- **Your own domain** — opens 80/443; Caddy auto-provisions a Let's Encrypt cert. Point the domain's
  DNS at the host first.

It writes `.env` (gitignored), builds the images, brings the stack up, seeds the database, and prints
the console URL and the generated **admin** password (shown once — save it, then change it in the UI).

Stack: `postgres` + `server` (Tomcat) + `caddy` (serves the SPA, proxies `/rest`, `/files`,
`/agent/ws`) + optional `cloudflared`. Postgres and the server publish **no** host ports.

Manage it:
```bash
docker compose --profile cloudflare up -d                                   # cloudflare mode
docker compose -f docker-compose.yml -f docker-compose.domain.yml up -d     # own-domain mode
docker compose logs -f server
docker compose down
```

## Option C — Native (no Docker)

Debian/Ubuntu, as root. The leaner path: Postgres + Tomcat on the host; you terminate TLS yourself
(your reverse proxy/cert, or Caddy in front). The installer reads the console's Node requirement from
`web/package.json`; if the installed Node/npm does not satisfy it, it installs the needed Node major
from the signed NodeSource APT repository. This avoids Debian 12's obsolete stock Node 18 package.

```bash
sudo ./setup.sh --native      # → install/install-native.sh
```

**Upgrading a native install** is the same command after `git pull`. The installer detects existing data and
asks **Keep** (default, just press Enter) or **Erase** (requires typing `ERASE`). Keep redeploys the code, runs
migrations, and leaves configurations, devices, users and the enrollment secret untouched; a `pg_dump` is written
to `/opt/dallycontrol/backups/` first. Unattended: `sudo ./setup.sh --native -y` never erases; set `REPLACE_DATA=yes` to
opt into a wipe, `HTTP_PORT=9090` to pick the port. Only missing packages are installed, and a JDK 17 found via
`JAVA17_HOME` or under `/opt` is used as-is (Debian 13 ships no `openjdk-17-jdk`).

Tomcat runs as the unprivileged `dallycontrol` system user under systemd (`dallycontrol-server.service`, enabled at boot).
Manage it like any other service:

```bash
systemctl status dallycontrol-server        # health, PID, recent log lines
systemctl restart dallycontrol-server       # after editing conf/Catalina/localhost/ROOT.xml
journalctl -u dallycontrol-server -f        # follow Tomcat's stdout/stderr
```

The installer stops whatever it started before (the unit, or a pre-0.2.9 root Tomcat launched with `catalina.sh`) and
refuses to continue if the chosen port is held by anything else, so it never kills a process it does not own. The JDK
does not run as root, and the installer does not open ports 80/443; front it with your own TLS proxy.

To enable password-recovery email on a native install, pass the same SMTP settings used by the Docker deployment when
running the installer. They are written into the root-only Tomcat context file:

```bash
SMTP_HOST=smtp.example.com SMTP_PORT=587 SMTP_STARTTLS=true \
SMTP_USERNAME=dallycontrol SMTP_PASSWORD='...' SMTP_FROM=mdm@example.com \
sudo ./setup.sh --native
```

`SMTP_SSL` and `SMTP_STARTTLS` accept `true` or `false` (both default to `false`).

## Uninstalling

**Docker (`setup.sh` or the quick start).** Everything lives in the compose project `dallycontrol` plus the directory
you ran it from (`./dallycontrol` for the quick start). Take a dump first if you want one:

```bash
docker compose exec -T postgres pg_dump -U dallycontrol -Fc dallycontrol > dallycontrol-final.dump
docker compose down -v --remove-orphans     # stops containers and DELETES the volumes (database, uploads, certs, backups)
rm -f .env                                  # secrets; the directory itself can go too for a quick-start install
docker image rm $(docker image ls 'ghcr.io/david0ql/dallycontrol-*' -q) 2>/dev/null   # optional: free the images
```

`docker compose down` without `-v` keeps the data volumes, so a later `./setup.sh` picks up where you left off.

**Native.** `sudo ./install/uninstall-native.sh` shows exactly what it will remove (Tomcat under `/opt/dallycontrol-tc`,
the app dir `/opt/dallycontrol`, the `dallycontrol-server` and `dallycontrol-supervisor` units and the supervisor's settings in
`/etc/dallycontrol`, the `dallycontrol` system user, the install log, and the `dallycontrol` database + role),
writes a final `pg_dump` to `/root`, and only proceeds when you type `UNINSTALL`. `--keep-data` removes the code
and services but leaves the database, `/opt/dallycontrol/files` and `/opt/dallycontrol/backups` in place; `-y` skips the
prompt for scripted use. Packages installed by apt, your reverse proxy and the git checkout are never touched.

Devices that are still enrolled keep polling the old server URL until they are factory-reset or re-provisioned;
if you are migrating rather than retiring, keep `BASE_URL` reachable (or point DNS at the new host) so they
follow.

## Enrolling devices

One prebuilt agent APK works for **every** deployment — the server URL is delivered in the
enrollment QR (`com.dallycontrol.SERVER_URL`), not baked into the APK. Host the APK on your server and
generate the QR from the console's **Enroll** page; it embeds your `BASE_URL`, the APK location, and
a single-use token.

### Over USB (ADB) — when QR provisioning is blocked

On devices with Google Play services, Play Protect blocks QR / zero-touch / Knox provisioning of any
device-policy app that is not on Google's DPC allowlist, which includes a self-signed DallyControl agent. USB
enrollment is not subject to that check. On a factory-reset device with USB debugging enabled and **no
accounts** added:

```bash
scripts/adb-enroll.sh --server https://mdm.example.com --apk dallycontrol-agent.apk --admin-user admin
# or with a token minted on the Enroll page:  --token <token>
# bind it to a configuration:                 --configuration-id <id>
```

The script installs the APK, makes it Device Owner (`dpm set-device-owner`), then hands it the server URL
and the single-use token through `AdbProvisionReceiver` (only the shell can send that broadcast). The agent
applies the same Device-Owner baseline as QR provisioning and enrolls within seconds. `--serial` picks a
device when several are attached; run it once per device (a USB hub and a loop do a whole batch).

## Remote view/control (optional)

Remote support uses droidVNC-NG on the device and a repeater on the server (see
[ADR 0010](docs/adr/0010-remote-control-droidvnc.md)). On the server:

```bash
COMPOSE_PROFILES=remote docker compose up -d      # adds vnc-repeater, websockify (noVNC viewer), websockify-device
```

No extra port to open: devices reach the repeater through an encrypted WebSocket on the server's own HTTPS origin
(`/remote/device/`, allowed only with the device's secret and a session queued for it), so the whole session is
encrypted by the same TLS as the API. The repeater's plain port (`REPEATER_PORT`, 5500) is published on loopback
only. Agents older than the tunnel can only dial that port in clear; if you still run some, publish it with
`REPEATER_BIND=0.0.0.0` and keep it behind a VPN. The console shows per device whether a session is encrypted.

Per device, enroll over USB with remote support (Android 7+):

```bash
APK=$(scripts/fetch-droidvnc.sh /tmp)                                   # pinned droidVNC-NG release
scripts/adb-enroll.sh --server https://mdm.example.com --apk dallycontrol-agent.apk --admin-user admin \
  --remote --vnc-apk "$APK"
```

Start a session from the console: device page → **Remote** → **View & control** (or **View only**). The screen
opens inside the console with Android's soft keys under it (◁ Back, ○ Home, □ Recents; Power, Volume and Rotate
on the left); **Open in new tab**, **Full screen** and **End session** are next to it. From a terminal,
`scripts/remote-session.sh --api https://mdm.example.com --device <device id>` (`--view-only`, `--stop`) prints the
same viewer link.

**Instant support for locked phones.** In the default battery-saver connectivity mode a phone that is locked and
on battery drops its live connection and picks up new commands only at its next heartbeat, a few minutes later.
Devices that need immediate support should run in **Always-on**: the Remote tab offers **Set Always-on** when a
device is in battery-saver, and the same action (**Connectivity: Always-on**) runs for many devices at once from
the device list's **Actions**. Always-on costs some battery.

## Updates & recovery

A decoupled **supervisor** service polls your GitHub releases, verifies the minisign-signed manifest,
and can apply updates to the `server` + `caddy` images — backing the database up first and rolling
back automatically if the new version fails its health check. It stays up even while the server is
mid-restart, so "update available" and the recovery page are always reachable.

Set these in `.env` (the wizard seeds them; add by hand for an existing deploy):

| Variable | Meaning |
|----------|---------|
| `GITHUB_REPO` | `owner/repo` to poll for releases (required to enable updates). |
| `UPDATE_CHANNEL` | `stable` (default) or `beta` (allows prereleases). |
| `POLL_INTERVAL_HOURS` | How often to check (default `6`). |
| `GITHUB_TOKEN` | Optional — raises the API rate limit / reads a private repo. |
| `IMAGE_OWNER` | GHCR owner (lowercase) the versioned images live under. |
| `SERVER_VERSION` / `WEB_VERSION` | Running image tags **without the `v`** (`0.2.6`, not `v0.2.6`); bumped automatically on apply. `./setup.sh` builds every image from the checkout, so on every run it sets them to the checkout's version (whatever `IMAGE_OWNER` is): the images are named after the code they hold. |
| `CURRENT_VERSION` | The running release, compared with GitHub's latest to decide "update available". Bumped on apply. `./setup.sh` rewrites it on every run from the checkout's nearest release tag (`vX.Y.Z` or `vX.Y.Z-pre`; other tags are skipped), like the native installer, and with a registry `IMAGE_OWNER` refuses a checkout older than it, or one without a release tag (see below). |
| `SUPERVISOR_VERSION` | The supervisor's image tag. Apply never changes it (the supervisor never updates itself). The quick start tracks `latest`, so `docker compose pull && docker compose up -d` delivers supervisor fixes; pin it only if you want to freeze it (then bump it by hand to pick up fixes). `./setup.sh` builds the supervisor from the checkout and sets it to the checkout's version on every run. |
| `APPLY_SUPPORTED` | `1` shows one-click **Update**, `0` shows the manual steps instead. `./setup.sh` rewrites it on every run from `IMAGE_OWNER` (`local` or unset → `0`); the source compose file defaults to `0`, the release compose to `1`. |
| `AUTO_UPDATE` | `1` to apply verified releases unattended (also toggleable in **Settings**). |

- **One-click:** when a verified update is available, a banner appears in the console; an admin clicks
  **Update**, watches the live progress, and the stack rolls back on its own if anything fails.
- **Unattended:** turn on **Automatic updates** in Settings (or `AUTO_UPDATE=1`) to apply each verified
  release without a prompt. A release that fails its rollback is never auto-retried.
- **Recovery:** `https://<host>/recovery` shows live apply state and, on quick-start installs, a **Roll back**
  button. While signed in, no token is needed. If the server is down, paste the break-glass recovery token, read with:
  `docker compose exec supervisor cat /backups/recovery.token`. From-source Docker and native installs
  (`APPLY_SUPPORTED=0`) can't roll back one-click: their recovery page hides Roll back and shows the manual steps
  instead: `git pull && ./setup.sh` (Docker from source) or `git pull && sudo ./install/install-native.sh` (native).
  Native installs don't proxy it: the supervisor listens on loopback only, so open it from the host with
  `curl 127.0.0.1:9000/recovery` (not `https://<host>/recovery`).
- **Source (build) deploys** can't auto-pull, so setup.sh hides one-click Update (`APPLY_SUPPORTED=0`); update with
  `git pull && ./setup.sh`. Re-running `./setup.sh` (rather than `docker compose up -d --build` alone) is what refreshes
  `CURRENT_VERSION`, the image tags and `APPLY_SUPPORTED`; without a release tag (no git, tags not fetched, or only
  non-release tags) it keeps the old values and warns.
- **`./setup.sh` with a registry `IMAGE_OWNER`** (one-click Update on) still builds the stack from the checkout, and
  apply may since have moved it to a newer release. If the checkout is older than the running `CURRENT_VERSION`,
  setup.sh stops before changing anything (`running 0.4.0, checkout is 0.3.1 — git pull first, or re-run with
  --allow-downgrade`). `git pull` first; `./setup.sh --allow-downgrade` builds and runs the older code on purpose
  (against the current database, which is not rolled back). It also stops when it can't tell which version the
  checkout is (no readable release tag: a source tarball, or tags not fetched); build from a tagged git checkout, or
  pass `--allow-downgrade` to build that code anyway. It then keeps the image tags and `CURRENT_VERSION` that `.env`
  already holds: on a fresh install that is `0.0.0`, so every release shows as an update.
- `./setup.sh` rejects an unknown option with a usage error (Docker mode). With `--native` it passes its other flags
  (such as `-y`), wherever they stand, to the native installer; `--reset` and `--allow-downgrade` are Docker-mode
  flags, and `--native` ignores them.
- Older agents keep working across server updates (versioned `/agent/v1` contract; see
  `docs/adr/0009-agent-v1-contract-stability.md`).

## Server logs

The server logs to stdout only, at INFO: read it with `docker compose logs -f server` (Docker) or
`journalctl -u dallycontrol-server -f` (native). Audit events (sign-ins, password changes, edits to devices, configurations,
applications and groups) are the lines of the `AuditLogger` logger, for example
`docker compose logs server | grep AuditLogger`; the audit plugin also stores them in the `plugin_audit_log` table.
Tomcat also writes its own files (`catalina.<date>.log`, which repeats its console lines and the database migrations,
and the HTTP access log) under `/usr/local/tomcat/logs` in the container and `/opt/dallycontrol-tc/logs` on native installs.

Docker's default `json-file` log driver keeps container logs without a size limit. To cap them, set `log-opts` in
`/etc/docker/daemon.json` (for example `"log-opts": {"max-size": "10m", "max-file": "5"}`) and recreate the
containers. journald caps the journal on its own.

An install upgraded from v0.2.1–v0.3.x keeps `/opt/dallycontrol/log4j-dallycontrol.xml` (and `/opt/dallycontrol/logs/`, if a development build
created it). The server no longer reads or writes them; delete them if you like.

For a temporary DEBUG log, put a log4j 1.2 XML config at `/opt/dallycontrol/log4j-debug.xml` and start the server with
the JVM flag `-Dlog4j.configuration=file:///opt/dallycontrol/log4j-debug.xml`:
- Docker: `docker compose cp log4j-debug.xml server:/opt/dallycontrol/`, add
  `SERVER_JAVA_OPTS=-Dlog4j.configuration=file:///opt/dallycontrol/log4j-debug.xml` to `.env` (the server gets it as
  `JAVA_OPTS`), then `docker compose up -d server`. Quote a value that holds several flags
  (`SERVER_JAVA_OPTS="-Xmx1g -Dlog4j.configuration=file:///opt/dallycontrol/log4j-debug.xml"`): `setup.sh` reads `.env` as
  shell, and an unquoted space stops it. A quick-start install made before this release also needs the line
  `JAVA_OPTS: ${SERVER_JAVA_OPTS:-}` under `server:` → `environment:` in its `docker-compose.yml` for that.
- Native: copy the file there (readable by the `dallycontrol` user), run `systemctl edit dallycontrol-server`, add
  `Environment=JAVA_OPTS=-Dlog4j.configuration=file:///opt/dallycontrol/log4j-debug.xml` under `[Service]`, then
  `systemctl restart dallycontrol-server`.

Undo it the same way afterwards: DEBUG logs every SQL statement.

## Health checks

Docker installs answer two unauthenticated probes at the edge, for uptime monitors. Each returns `200 ok` or a
`503` with a short reason, never the console page. A trailing slash (`/healthz/`, `/healthz/supervisor/`) works too.

| Probe | `200 ok` when | `503` when |
|-------|---------------|------------|
| `https://<host>/healthz` | Caddy is up **and** the API server answers `GET /rest/public/name` with a 2xx (the probe an update uses to decide the new version is healthy) | `server unavailable`: the server is stopped, still starting, or answering with errors |
| `https://<host>/healthz/supervisor` | the updater/recovery supervisor answers its own `/healthz` | `supervisor unavailable`: the supervisor is down. The console, the API and enrolled devices keep working, but update checks, the recovery page and the `/files/agent.apk` mirror that new enrollments download do not |

No answer at all means the edge itself is down. Neither probe queries the database: `docker compose ps` shows
`postgres` as `healthy` from its own `pg_isready` check. `/healthz` also fails, as it should, for the minute or so
an update takes to recreate the server.

**Native installs** have no edge probes: `/healthz` and `/healthz/supervisor` answer `404`, never the console page
(an install from before this release serves the console there until its next installer run, `sudo ./setup.sh
--native -y`). Point the monitor at `https://<host>/rest/public/name` through your proxy, and check the supervisor,
which listens on loopback `:9000` only, on the host with `curl -fsS 127.0.0.1:9000/healthz` or
`systemctl is-active dallycontrol-supervisor`.

## Security notes

See [docs/SECURITY-HARDENING.md](docs/SECURITY-HARDENING.md) for the audit this release went through, what was fixed
and the residual risks. `scripts/security-check.sh` re-checks the fixes against a running install.

- Secrets (`DB_PASSWORD`, `HASH_SECRET`, admin password, JWT signing key) are generated per install; `.env` is created
  owner-only before any secret is written into it.
- Only the REST surface the console, the agent and the edge use is reachable without a session (an allowlist in the
  server, repeated in Caddy): console login/logout/options, password reset, the agent protocol and a health probe.
  The legacy Headwind endpoints (launcher sync, notification queue, plugin device APIs, stats, signup, QR, Swagger,
  `/rest/public/jwt/login`) answer 404.
- The JWT signing key still signs tokens accepted on `/rest/private/*`; it is generated once into the server's data
  volume (`/opt/dallycontrol/jwt.secret`, mode 600) or pinned with `SERVER_JWT_SECRET=<openssl rand -hex 64>` in
  `.env` (hex, a multiple of 4 characters, at least 128 long — the server refuses anything else).
- TLS everywhere (Cloudflare or Caddy/Let's Encrypt), HSTS, a strict Content-Security-Policy on the console, no
  framing by other sites. DB + server ports are never published; the VNC repeater's plain port binds loopback.
- Passwords are stored as PBKDF2-HMAC-SHA256 with a per-user salt; accounts lock after 5 failed logins (5 min,
  doubling to 30); the session id is renewed at login; the session cookie is HttpOnly, SameSite=Lax and Secure when
  `BASE_URL` is https; sessions expire after 8 h idle.
- The default password `admin` is refused at login (only the dev stack allows it). The installers keep the edge down
  until the admin's generated password is in place; the admin must set their own on first login.
- Containers run with `no-new-privileges` and all Linux capabilities dropped except the few each entrypoint needs.
  Images are pinned by digest (PostgreSQL 17, cloudflared) or by version.
- The supervisor mounts the Docker socket (to drive updates) and is trusted: it applies only **minisign-verified**
  manifests whose images are **pinned by digest**, and only for **authorized** callers (admin session, or the
  recovery token). Apply/rollback only ever recreate `server`/`caddy` — never `postgres` or the supervisor itself.
  Pre-update database backups are owner-only; the newest 5 are kept.
- On native installs the supervisor runs as the unprivileged `dallycontrol` user (like Tomcat), with its settings in the
  root-owned `/etc/dallycontrol/supervisor.env`. A `GITHUB_TOKEN` there reaches the supervisor's environment, which that user
  can read, so use a read-only token.
- The native installer stops if the `dallycontrol` account has a crontab or `at` jobs (it never needs any): inspect them
  (`crontab -l -u dallycontrol`, `atq`), remove them (`crontab -r -u dallycontrol`, `atrm <id>`) and re-run.

## Before going live (checklist)

1. `BASE_URL` is the public **https** URL (the session cookie is Secure only then).
2. The host firewall allows only **80/443** (own-domain mode) or nothing inbound (Cloudflare Tunnel). Do not publish
   `REPEATER_PORT`; devices reach the repeater through the encrypted tunnel.
3. `.env` is mode 600 and its secrets are the generated ones (`DB_PASSWORD`, `HASH_SECRET`); nothing in it came from
   `docker/dev.env`, and `ALLOW_DEFAULT_PASSWORD` is not set anywhere.
4. You signed in once and replaced the generated admin password; create named users with the least role they need
   (Observer for read-only) instead of sharing `admin`.
5. Run the checks against the install (or a staging copy — it creates and deletes one temporary read-only user):
   `ADMIN_PW='<admin password>' scripts/security-check.sh https://mdm.example.com` — every line must PASS.
6. Configure SMTP in `.env` if you want email password recovery.
7. Keep backups of the `pgdata` and `dallycontroldata` volumes off the host.

### Existing installs on PostgreSQL 14

PostgreSQL 14 reaches end of life on 2026-11-12, and this release runs PostgreSQL 17. PostgreSQL does not open an older
major version's data directory, so an existing install moves its data once, with the stack stopped:

```bash
docker compose exec -T postgres pg_dumpall -U dallycontrol > dallycontrol-pg14.sql   # BEFORE pulling the new compose
# check the dump (non-empty, ends with "PostgreSQL database cluster dump complete"), keep a copy off the host
docker compose down                        # stops the stack; keeps the volumes
docker volume rm <project>_pgdata          # only the database volume (docker volume ls to get the exact name)
# update docker-compose.yml to this release, then:
docker compose up -d postgres
docker compose exec -T postgres psql -U dallycontrol -d postgres < dallycontrol-pg14.sql   # "already exists" notices are expected
docker compose up -d
```

Compare a few row counts (devices, users, configurations) before and after.
