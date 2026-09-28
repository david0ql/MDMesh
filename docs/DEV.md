# Dev environment

Three planes run independently; you only need the toolchain for the one you are changing.

## Prerequisites

| Plane | Needs |
|-------|-------|
| Control plane (server) | Docker + Docker Compose; the scripts also need bash, curl, python3, openssl and md5sum (GNU coreutils); the fast Java loop needs JDK 17 + Maven |
| Admin frontend (web)   | Node 22 (Vite 8 needs ≥ 20.19), npm |
| Device agent           | JDK 17, Android SDK (cmdline-tools), an AOSP emulator or a factory-reset device |

## 1. Control plane (the dev stack)

The dev stack is the production stack (`docker-compose.yml`: Postgres, server, Caddy with the console, supervisor)
plus a thin overlay, `docker-compose.dev.yml`, that publishes loopback-only ports and a debugger port. Its settings
live in `docker/dev.env`: the project name `mdmesh-dev`, dev-only passwords, `:dev` image tags and an inert
supervisor. Pass that file on **every** compose command for the dev stack. It replaces `.env`, so a production `.env`
in the same checkout is never read, and the overlay refuses to load without it.

```bash
docker compose --env-file docker/dev.env up -d --build    # build + start; the first boot runs Liquibase
scripts/dev-seed.sh                                        # first run: seed the database like the installers do
```

| What | Where |
|------|-------|
| Console | <http://localhost:8088>, login **admin / admin** (after `scripts/dev-seed.sh`) |
| API (direct to Tomcat) | <http://localhost:8080/rest/> |
| Postgres | `localhost:5432`, database, user and password `mdmesh` |
| Debugger (JDWP) | attach your IDE to `localhost:5005` |

Every port binds to `127.0.0.1`. If one is taken, export `DEV_WEB_PORT`, `DEV_API_PORT`, `DEV_PG_PORT` or
`DEV_DEBUG_PORT` before running compose (shell variables override `docker/dev.env`). If you move the console, also
export `BASE_URL=http://localhost:<port>`.

Configuration is environment only: `docker/entrypoint.sh` renders Tomcat's `ROOT.xml` from it at every start. Change a
value in `docker/dev.env` (or export it) and run the `up -d` command again.

`scripts/dev-seed.sh` seeds through `install/lib/db.sh`, the same code the installers run, then sets the admin
password through the real first-login flow: `admin`, or the value of `DEV_ADMIN_PASSWORD` if you export one. On a
database that is already seeded it only re-applies the post-seed repairs, and finishes the admin password reset if an
earlier run was interrupted before it. It refuses to run unless the stack's containers were created with
`docker-compose.dev.yml`, so it never seeds a production install.

```bash
docker compose --env-file docker/dev.env logs -f server              # server log
docker compose --env-file docker/dev.env up -d --build server        # rebuild + restart the server image
docker compose --env-file docker/dev.env down                        # stop; keeps the data
docker compose --env-file docker/dev.env down -v                     # reset: also deletes the dev database and files
```

The server logs to stdout at INFO, from `server/src/main/resources/log4j.xml` (its only log config). For DEBUG while
you work, raise a level there locally and use the fast Java loop below. Do not commit it: `LoggingConfigTest` (the
server tests, which T0 runs) fails if the root or `AuditLogger` level is not INFO. A dev stack created before this
change keeps the old external config across WAR reloads: recreate the server once (`docker compose --env-file
docker/dev.env up -d --force-recreate server`).

`down -v` deletes the server's data volume too (`/opt/mdmesh`: uploaded files and the JWT signing key in
`jwt.secret`), so the next start generates a new key. With `--env-file docker/dev.env`, `down -v` deletes the
`mdmesh-dev` project's volumes, unless your shell exports `COMPOSE_PROJECT_NAME`: shell variables beat the env file.
Before a `down -v`, check which project it will hit: `docker compose --env-file docker/dev.env config | head -1` must
print `name: mdmesh-dev`.

Do not run the dev stack in a checkout that also runs a real install (`./setup.sh` writes a `.env` there). The
`MDMESH_DEV` guard in `docker-compose.dev.yml` stops `up` and `config` without `--env-file docker/dev.env`, but not
`down -v`, `stop`, `rm`, `exec`, `logs` or `ps`. Run without it there, those act on the production project, even when
they name the dev file, because compose falls back to the project name in `.env`.

**Fast Java loop.** Rebuilding the server image runs the whole Maven build inside Docker (over a minute). For quicker
turns, build on the host (JDK 17) and copy the WAR into the running container; Tomcat reloads it within about 20 s:

```bash
cp -n server/build.properties.example server/build.properties     # once
mvn -pl server -am package -DskipTests
docker compose --env-file docker/dev.env cp server/target/launcher.war server:/usr/local/tomcat/webapps/ROOT.war
```

The copied WAR lasts until the container is recreated (`down`, or `up -d --build` after a source change); then the
image's own build is back.

API reference: the server publishes its Swagger 2.0 description at `/rest/swagger.json` (no UI is bundled). To browse it,
`curl --create-dirs -o /tmp/api/swagger.json http://localhost:8080/rest/swagger.json` then
`docker run --rm -p 8081:8080 -e SWAGGER_JSON=/api/swagger.json -v /tmp/api:/api swaggerapi/swagger-ui` → <http://localhost:8081>
(or open the file in editor.swagger.io).

## 2. Admin frontend (React)

```bash
cd web
npm install
npm run dev          # http://localhost:5173 with hot reload, proxied to the dev stack
```

The Vite dev server proxies `/rest`, `/files`, `/agent/ws`, `/update` and `/recovery` to the dev stack's Caddy
(<http://localhost:8088>), which routes them the way production does. Point it elsewhere with
`VITE_DEV_PROXY_TARGET` (see `web/README.md`). It listens on every interface, so you can open it from a phone on your
LAN; run it only on a network you trust.

## 3. Device agent (Kotlin)

```bash
cd agent-android
./gradlew :app:assembleDebug
```

To enroll an emulator or test device as Device Owner over ADB, follow "ADB Device-Owner dev enrollment loop" in
`agent-android/README.md` (debug builds are `com.mdmesh.agent.debug`). Without a QR code, the agent uses the
`MDM_BASE_URL` its build type bakes in (`agent-android/app/build.gradle.kts`). The agent has no cleartext-HTTP
exception, so a device needs a server it can reach over HTTPS (see `DEPLOY.md`). The loopback dev stack serves the
console, the API and the scripted agent loop below.

## Real devices, emulators and remote control

`scripts/adb-enroll.sh` enrolls an emulator or USB device into the dev stack (`--server http://10.0.2.2:8088
--api-url http://localhost:8088 --debug-build`; add `--remote --vnc-apk "$(scripts/fetch-droidvnc.sh /tmp)"` for
remote control). The dev stack runs the `remote` compose profile (repeater on loopback `:5500`, viewer at
<http://localhost:8088/remote/vnc/vnc.html>); the device page's Remote tab starts a session, or `scripts/remote-session.sh --api http://localhost:8088 --device <id>`
prints a session link. The functional device test, the emulator matrix and the remote end-to-end test are
described in [TESTING.md](TESTING.md).

## End-to-end agent loop (Agent v1)

`scripts/agent-v1-e2e.sh` drives the whole protocol against a running server with `curl` playing the device: enroll →
mint token → queue command → authenticated, capability-gated check-in → ack, plus the command and rollout scenarios
built on them. On the dev stack, after `scripts/dev-seed.sh`:

```bash
scripts/agent-v1-e2e.sh http://localhost:8080      # expect "RESULT: PASS=<n> FAIL=0"
```

It signs in as `admin` with `ADMIN_PW` (default `admin`, what `dev-seed.sh` sets). If you seeded with
`DEV_ADMIN_PASSWORD`, pass the same value as `ADMIN_PW`. For another server, seed it the way `scripts/dev-seed.sh`
does and pass `ADMIN_PW=<password>`.

CI runs the same loop as tier T1 (`.github/workflows/t1-e2e.yml`): the dev stack's `postgres` and `server` services,
`scripts/dev-seed.sh` with a random admin password, then `scripts/agent-v1-e2e.sh`. Each run gets its own compose
project, server image tag and ephemeral loopback ports, so it never collides with your dev stack or another run. It
runs on pull requests and pushes to `main` that touch the server modules, `proto/`, `install/`, the server image,
the dev stack files or the suite (the `e2e` filter in the workflow has the exact list), and on any change under
`.github/`, to a compose file, a lockfile or a file named `Dockerfile*`. A failed run uploads the compose and Tomcat
logs as the `t1-e2e-logs` artifact.

The remaining step that needs a provisioned box is the **real on-device run**: build the agent, enroll an AOSP
emulator as Device Owner via ADB, and watch a `policy.apply` apply on the device.
